# The Apogee dedicated server.
#
# The same GameServer a phone hosts a game with, wrapped in a process that
# keeps its world on disk and exposes an admin control socket. There is one
# implementation of the simulation and this is not a privileged copy of it.
#
# Build context is the repository root:
#     docker compose up --build

# JDK 25 to match gradle/gradle-daemon-jvm.properties. On a lower JDK, Gradle
# honours that pin by *downloading* a matching toolchain inside the build
# container - minutes of build time and a network dependency, for a JDK the
# base image could simply have been.
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
# modules out of a JDK's eighty-odd. Shipping a whole JRE cost 159MB for a
# small fraction of it.
#
# The module list is computed here, from the jars that were just built, so a
# new dependency needing another module is caught at build time rather than
# at startup.
RUN set -eu; \
    MODULES="$(jdeps --print-module-deps --ignore-missing-deps --multi-release 21 \
        /src/dedicated/build/install/apogee-server/lib/*.jar)"; \
    echo "Linking a runtime for: $MODULES"; \
    jlink --add-modules "$MODULES" \
          --strip-debug --no-man-pages --no-header-files \
          --compress=zip-6 \
          --output /javaruntime

# Everything that needs a shell happens here, because the runtime image has
# none: the account, the directory layout, and the permissions.
RUN set -eu; \
    groupadd --gid 10002 apogee; \
    useradd --uid 10002 --gid 10002 --no-create-home --shell /usr/sbin/nologin apogee; \
    mkdir -p /stage/opt/apogee /stage/state /stage/run/apogee /stage/tmp; \
    cp -a /src/dedicated/build/install/apogee-server/lib /stage/opt/apogee/lib; \
    chmod -R a+rX /stage/opt/apogee; \
    chown -R 10002:10002 /stage/state /stage/run /stage/tmp; \
    chmod 1777 /stage/tmp


# A static busybox, solely for the healthcheck.
#
# Distroless has no shell and no coreutils, so `test -S` has nothing to run.
# One static binary is about 1MB and buys a healthcheck that means something -
# and, when a server misbehaves at 3am, a way in with `docker exec ... sh`.
FROM busybox:stable-musl AS busybox


# Distroless: glibc, ca-certificates and nothing else. No shell, no package
# manager, no coreutils - so the attack surface is the JVM and our own code,
# which is the point. It also takes the image from 132MB to well under 90.
FROM gcr.io/distroless/base-debian12

COPY --from=build /javaruntime /opt/java
COPY --from=build /stage/opt/apogee /opt/apogee
COPY --from=build /stage/state /state
COPY --from=build /stage/run /run
COPY --from=build /stage/tmp /tmp
# The account itself. Distroless ships only `nonroot`, and this uid has to
# match the web container's so the two agree about the shared socket.
COPY --from=build /etc/passwd /etc/passwd
COPY --from=build /etc/group /etc/group
COPY --from=busybox /bin/busybox /bin/busybox

ENV JAVA_HOME=/opt/java \
    APOGEE_STATE_DIR=/state \
    APOGEE_CONTROL_SOCKET=/run/apogee/control.sock \
    APOGEE_PORT=45678 \
    APOGEE_AUTOSAVE_SECONDS=60 \
    APOGEE_LAN_DISCOVERY=1

VOLUME ["/state", "/run/apogee"]
EXPOSE 45678/tcp

USER 10002:10002

# The control socket only exists once the server is actually serving, which
# makes it a better liveness signal than the process being up.
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s \
    CMD ["/bin/busybox", "test", "-S", "/run/apogee/control.sock"]

# Java is invoked directly rather than through Gradle's start script, which is
# a shell script and has nothing to run it here. `lib/*` is a classpath
# wildcard the JVM expands itself, not a glob - exec form passes it through
# unexpanded, which is what we want.
#
# Extra JVM tuning goes in JAVA_TOOL_OPTIONS, which the JVM reads from the
# environment on its own; there is no shell to assemble a command line.
ENTRYPOINT ["/opt/java/bin/java", \
            "-XX:MaxRAMPercentage=75", \
            "-cp", "/opt/apogee/lib/*", \
            "com.rm.apogee.dedicated.MainKt"]
