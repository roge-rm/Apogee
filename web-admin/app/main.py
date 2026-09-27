"""Apogee dedicated server: the web admin.

This is one of the two containers in docker-compose.yml. It owns no game
state at all. Every page is a rendering of what the server said over the
control socket, and every button is one control command. If this container
isn't running, the game isn't affected at all.

What the page shows is small on purpose for now: status, players, the log, and
the three actions an operator really needs at 2am. Settings, bans and craft
management come later, and belong behind the same control channel.
"""

import asyncio
import logging
import os

from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from . import auth
from .control import Control, ControlError, ServerDown

logging.basicConfig(level=os.environ.get("LOG_LEVEL", "INFO"))
LOG = logging.getLogger("apogee.web")

HERE = os.path.dirname(os.path.abspath(__file__))
control = Control()

app = FastAPI(title="Apogee Server Admin", docs_url=None, redoc_url=None)
app.mount("/static", StaticFiles(directory=os.path.join(HERE, "static")), name="static")
templates = Jinja2Templates(directory=os.path.join(HERE, "templates"))


@app.on_event("startup")
async def startup():
    # Fails the container straight away instead of serving an open panel.
    auth._password()
    LOG.info("Admin ready; control socket %s", control.path)


def _authorised(request):
    return auth.valid(request.cookies.get(auth.COOKIE))


def _redirect_to_login():
    return RedirectResponse("/login", status_code=303)


# --- session ---------------------------------------------------------------


@app.get("/login", response_class=HTMLResponse)
async def login_page(request: Request, bad: int = 0):
    return templates.TemplateResponse(
        request, "login.html", {"bad": bool(bad)}
    )


@app.post("/login")
async def login(password: str = Form("")):
    if not auth.check(password):
        # Slow on purpose, and vague on purpose. There's one password and one
        # operator, and a fast, specific "wrong password" is only useful to
        # somebody guessing.
        await asyncio.sleep(1.0)
        return RedirectResponse("/login?bad=1", status_code=303)

    response = RedirectResponse("/", status_code=303)
    response.set_cookie(
        auth.COOKIE,
        auth.issue(),
        httponly=True,
        samesite="lax",
        secure=auth.secure_cookies(),
    )
    return response


@app.post("/logout")
async def logout(request: Request):
    auth.revoke(request.cookies.get(auth.COOKIE))
    response = RedirectResponse("/login", status_code=303)
    response.delete_cookie(auth.COOKIE)
    return response


# --- pages -----------------------------------------------------------------


@app.get("/", response_class=HTMLResponse)
async def dashboard(request: Request):
    if not _authorised(request):
        return _redirect_to_login()
    return templates.TemplateResponse(request, "dashboard.html", {})


# --- data, polled by the page ----------------------------------------------


@app.get("/api/state")
async def state(request: Request):
    """Everything the dashboard shows, in one call.

    It's one endpoint instead of three, because the page wants a consistent
    picture. A status from one moment and a player list from another make a
    dashboard that contradicts itself while you read it.
    """
    if not _authorised(request):
        return JSONResponse({"error": "unauthorised"}, status_code=401)

    try:
        status = await asyncio.to_thread(control.status)
        players = await asyncio.to_thread(control.players)
        log_lines = await asyncio.to_thread(control.log, 150)
    except ServerDown as down:
        # A state, not a failure, because the server might be restarting.
        return JSONResponse({"running": False, "reason": str(down)})
    except ControlError as refused:
        return JSONResponse({"running": False, "reason": str(refused)})

    return JSONResponse(
        {
            "running": True,
            "status": status,
            "players": players,
            "log": log_lines,
        }
    )


# --- actions ---------------------------------------------------------------


def _action(handler):
    """Runs one control command and reports the outcome to the page."""

    async def run(request: Request, **kwargs):
        if not _authorised(request):
            return JSONResponse({"error": "unauthorised"}, status_code=401)
        try:
            await asyncio.to_thread(handler, **kwargs)
            return JSONResponse({"ok": True})
        except (ServerDown, ControlError) as error:
            return JSONResponse({"ok": False, "error": str(error)}, status_code=200)

    return run


@app.post("/api/save")
async def save(request: Request):
    return await _action(lambda: control.save())(request)


@app.post("/api/announce")
async def announce(request: Request, message: str = Form("")):
    if not message.strip():
        return JSONResponse({"ok": False, "error": "Nothing to say"})
    return await _action(lambda: control.chat(message.strip()))(request)


@app.post("/api/kick")
async def kick(request: Request, name: str = Form("")):
    if not name.strip():
        return JSONResponse({"ok": False, "error": "No player named"})
    return await _action(lambda: control.kick(name.strip()))(request)


@app.post("/api/stop")
async def stop(request: Request):
    # The server saves on the way down, so this is safe to press. The
    # container's restart policy decides whether it comes back.
    return await _action(lambda: control.stop())(request)
