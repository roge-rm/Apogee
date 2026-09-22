# The Apogee dedicated server.
#
# The same GameServer a phone hosts a game with, wrapped in a process that
# keeps its world on disk and exposes an admin control socket. There is one
# implementation of the simulation and this is not a privileged copy of it.
#
# Build context is the repository root:
#     docker compose up --build

# JDK 25 to match gradle/gradle-daemon-jvm.properties. On a lower JDK,
# Gradle honours that pin by *downloading* a matching toolchain inside the
# build container - minutes of build time and a network dependency, for a JDK
# the base image could simply have been.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

# Wrapper and build scripts first, so a change to game source does not
# re-download Gradle on every rebuild.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties /src/
COPY gradle /src/gradle
RUN chmod +x gradlew && ./gradlew --version > /dev/null

COPY core /src/core
COPY net /src/net
COPY server /src/server
COPY dedicated /src/dedicated

# No local.properties and no ANDROID_HOME here, so settings.gradle.kts leaves
# :app out - see the note there. A JDK image has no Android SDK and would
# otherwise fail at configuration time, before any task ran.
RUN ./gradlew --no-daemon :dedicated:installDist

# Build a Java runtime containing only what the server actually loads.
#
# jdeps says that is java.base, java.instrument and jdk.unsupported - three
# modules out of a JDK's eighty-odd. A stock JRE base image is about 159MB of
# which the server touches a small fraction; this brings the runtime to around
# a third of that, and it is the single biggest thing in the image.
#
# Kept in step automatically: jdeps is run here against the jars that were
# just built, so a new dependency that needs another module is picked up at
# build time rather than failing at startup.
RUN set -eu; \
    MODULES="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 21 \
        /src/dedicated/build/install/apogee-server/lib/*.jar)"; \
    echo "Linking a runtime for: $MODULES"; \
    jlink --add-modules "$MODULES" \
          --strip-debug --no-man-pages --no-header-files \
          --compress=zip-6 \
          --output /javaruntime


# A plain base plus the linked runtime, rather than a full JRE image. The JRE
# was 159MB of the old 328MB, for three modules' worth of actual use.
#
# Debian slim rather than distroless: the healthcheck below is a shell test,
# and a distroless image would need it rewritten as a binary to save another
# 50-odd MB. Worth doing later, not worth the indirection now.
FROM debian:trixie-slim

COPY --from=build /javaruntime /opt/java
ENV JAVA_HOME=/opt/java
ENV PATH="/opt/java/bin:${PATH}"

# A fixed uid shared with the web image. The two containers pass a Unix socket
# between them through a volume, and the socket's file permissions are the
# only access control it has - so they have to agree on who they are.
RUN groupadd --gid 10002 apogee \
 && useradd --uid 10002 --gid 10002 --no-create-home --shell /usr/sbin/nologin apogee

COPY --from=build /src/dedicated/build/install/apogee-server /opt/apogee

# Make the distribution readable by the unprivileged user that runs it.
#
# Gradle's dependency cache stores downloaded artifacts 0600, and installDist
# copies them with that mode intact - so the application's own jars come out
# world-readable while every library it needs does not. The failure is
# spectacularly misleading: the main class loads fine and the process dies on
# NoClassDefFoundError for kotlin.jvm.functions.Function2, with the jar
# plainly present on a correct classpath.
#
# Ownership stays with root so the application directory cannot be modified by
# the account running it.
RUN chmod -R a+rX /opt/apogee

# Owned at image build time so the named volumes inherit it when Docker first
# populates them - otherwise they arrive root-owned and the server cannot
# write the world it is supposed to be persisting.
RUN mkdir -p /state /run/apogee \
 && chown -R apogee:apogee /state /run/apogee

ENV APOGEE_STATE_DIR=/state \
    APOGEE_CONTROL_SOCKET=/run/apogee/control.sock \
    APOGEE_PORT=45678 \
    APOGEE_AUTOSAVE_SECONDS=60 \
    APOGEE_LAN_DISCOVERY=1 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

VOLUME ["/state", "/run/apogee"]
EXPOSE 45678/tcp

USER apogee

# The control socket only exists once the server is actually serving, which
# makes it a better liveness signal than the process being up.
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s \
    CMD test -S "$APOGEE_CONTROL_SOCKET"

ENTRYPOINT ["/opt/apogee/bin/apogee-server"]
