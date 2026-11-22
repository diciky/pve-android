#!/usr/bin/env python3
"""Mock Proxmox VE server for smoke-testing PVEClient.
Listens on 127.0.0.1:8006 with self-signed TLS.
Validates: login form, auth cookie, CSRF token. Serves realistic PVE JSON.
"""
import http.server
import json
import ssl
import re
import urllib.parse

UPID = "UPID:pve1:0000ABCD:00001234:673FFFFF:vzdump:101:root@pam:"
VALID_TICKET = "PVE:root@pam:65F0ABCD1234"


def env(data):
    return json.dumps({"data": data}).encode()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass  # quiet

    # --- helpers ---
    def _path(self):
        return urllib.parse.unquote(self.path.split("?")[0])

    def _query(self):
        q = self.path.split("?")[1] if "?" in self.path else ""
        return urllib.parse.parse_qs(q)

    def _body(self):
        length = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(length).decode() if length else ""

    def _send(self, code, payload):
        self.send_response(code)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _authed(self):
        cookie = self.headers.get("Cookie", "")
        return cookie.startswith("PVEAuthCookie=PVE")

    def _csrf_ok(self):
        return self.headers.get("CSRFPreventionToken", "") == "CSRFToken:abcd1234"

    def GET(self):
        p, q = self._path(), self._query()
        if p == "/" or p.startswith("/index") or p.startswith("/#"):
            html = (b"<html><head><title>proxmox - pve1 - Proxmox Virtual Environment</title>"
                    b"</head><body><div id='novnc'>Proxmox VE Login</div></body></html>")
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", str(len(html)))
            self.end_headers()
            self.wfile.write(html)
            return
        if not self._authed():
            return self._send(401, env(None))
        if p == "/api2/json/cluster/resources":
            data = [
                {"id": "node/pve1", "type": "node", "node": "pve1", "status": "online",
                 "cpu": 0.12, "maxcpu": 8, "mem": 4 * 2**30, "maxmem": 16 * 2**30,
                 "disk": 20 * 2**30, "maxdisk": 500 * 2**30, "uptime": 86400},
                {"id": "qemu/101", "type": "qemu", "node": "pve1", "vmid": 101,
                 "name": "web-server", "status": "running", "cpu": 0.25, "maxcpu": 4,
                 "mem": 2**30, "maxmem": 4 * 2**30, "uptime": 3600, "tags": "prod;web"},
                {"id": "lxc/201", "type": "lxc", "node": "pve1", "vmid": 201,
                 "name": "dns-ct", "status": "stopped", "maxcpu": 2,
                 "mem": 0, "maxmem": 512 * 2**20},
                {"id": "storage/local", "type": "storage", "node": "pve1",
                 "storage": "local", "status": "available"},
            ]
            return self._send(200, env(data))
        if p == "/api2/json/nodes":
            return self._send(200, env([{"node": "pve1", "status": "online", "cpu": 0.1,
                                         "maxcpu": 8, "mem": 4 * 2**30, "maxmem": 16 * 2**30,
                                         "disk": 20 * 2**30, "maxdisk": 500 * 2**30, "uptime": 86400}]))
        if p == "/api2/json/nodes/pve1/status":
            return self._send(200, env({
                "cpu": 0.15, "uptime": 86400, "pveversion": "pve-manager/8.2.4",
                "kversion": "Linux 6.8.12-1-pve",
                "cpuinfo": {"cpu": "0.15", "cores": 8, "sockets": 1, "mhz": "3600",
                            "model": "Intel(R) Xeon(R)"},
                "memory": {"total": 16 * 2**30, "used": 4 * 2**30, "free": 12 * 2**30},
                "rootfs": {"total": 500 * 2**30, "used": 20 * 2**30, "free": 480 * 2**30,
                           "avail": 480 * 2**30},
                "loadavg": ["0.10", "0.15", "0.20"],
                "swap": {"total": 8 * 2**30, "used": 0, "free": 8 * 2**30},
                "waiting": 0}))
        if p == "/api2/json/nodes/pve1/storage":
            return self._send(200, env([
                {"storage": "local", "type": "dir", "content": "iso,vztmpl,backup",
                 "enabled": 1, "active": 1, "total": 500 * 2**30, "used": 20 * 2**30,
                 "avail": 480 * 2**30},
                {"storage": "disabled-store", "type": "nfs", "content": "backup",
                 "enabled": 0, "active": 0}]))
        m = re.match(r"^/api2/json/nodes/pve1/(qemu|lxc)/(\d+)/status/current$", p)
        if m:
            running = m.group(2) == "101"
            return self._send(200, env({
                "status": "running" if running else "stopped", "uptime": 3600 if running else 0,
                "cpu": 0.25 if running else 0,
                "memory": {"total": 4 * 2**30, "used": 2**30, "free": 3 * 2**30},
                "swap": {"total": 2**30, "used": 0, "free": 2**30},
                "netin": 1024.0, "netout": 2048.0, "diskwrite": 4096.0, "diskread": 8192.0,
                "maxdisk": 64 * 2**30, "ha": {"managed": 0}, "qmpstatus": "running",
                "runningMachine": "pc-i440fx-8.2"}))
        if p == "/api2/json/nodes/pve1/qemu/101/config":
            return self._send(200, env({
                "ostype": "l26", "cores": 4, "sockets": 1, "memory": 4096, "balloon": 0,
                "scsi0": "local-lvm:vm-101-disk-0,size=64G",
                "net0": "virtio=AA:BB:CC:DD:EE:FF,bridge=vmbr0",
                "onboot": 1, "name": "web-server", "agent": 1,
                "boot": "order=scsi0", "machine": "pc-i440fx-8.2"}))
        if p == "/api2/json/nodes/pve1/qemu/101/snapshot":
            return self._send(200, env([
                {"name": "current", "description": "You are here!",
                 "digest": "abc123", "running": 1},
                {"name": "snap1", "description": "before upgrade", "snaptime": 1730000000,
                 "vmstate": 0, "parent": "current"}]))
        if p == "/api2/json/nodes/pve1/storage/local/content" and q.get("content") == ["backup"]:
            return self._send(200, env([{
                "volid": "local:backup/vzdump-qemu-101-2026_11_21-10_00_00.vma.zst",
                "storage": "local", "format": "vma.zst", "size": 2**30,
                "ctime": 1730000000, "notes": "weekly backup"}]))
        if p == "/api2/json/nodes/pve1/tasks":
            return self._send(200, env([{
                "upid": UPID, "node": "pve1", "type": "vzdump", "user": "root@pam",
                "starttime": 1730000000, "endtime": 1730000600, "status": "OK",
                "id": "101", "description": "Backup"}]))
        if p == "/api2/json/cluster/tasks":
            # 真实 PVE：/cluster/tasks 不接受任何查询参数（含 limit），否则 400
            if q:
                return self._send(400, json.dumps({
                    "errors": {"limit": "property is not defined in schema and the "
                               "schema does not allow additional properties"},
                    "data": None, "message": "Parameter verification failed.\n"}).encode())
            return self._send(200, env([{
                "upid": UPID, "node": "pve1", "type": "vzdump", "user": "root@pam",
                "starttime": 1730000000, "endtime": 1730000600, "status": "OK",
                "id": "101", "description": "Backup"}]))
        if p == "/api2/json/nodes/pve1/services":
            return self._send(200, env([
                {"name": "pveproxy", "description": "PVE API Proxy Server", "state": "running"},
                {"name": "pvedaemon", "description": "PVE API Daemon", "state": "running"},
                {"name": "pvestatd", "description": "PVE Status Server", "state": "stopped"},
                {"name": "pve-cluster", "description": "The Proxmox VE cluster filesystem",
                 "state": "running"}]))
        if p == "/api2/json/nodes/pve1/syslog":
            service = (q.get("service") or [None])[0]
            lines = [
                {"n": 1, "t": "Nov 21 10:00:01 pve1 systemd[1]: Started pveproxy.service"},
                {"n": 2, "t": "Nov 21 10:00:05 pve1 pvestatd[1234]: status update time (5 seconds)"},
                {"n": 3, "t": "Nov 21 10:00:10 pve1 kernel: WARNING: cpu thermal"},
            ]
            if service:
                lines = [l for l in lines if service in l["t"]]
            return self._send(200, env(lines))
        if p == "/api2/json/nodes/pve1/journal":
            return self._send(200, env([
                {"t": "Nov 21 10:00:01 pve1 systemd[1]: Started Session 1",
                 "PRIORITY": 6, "MESSAGE": "Started Session 1"},
                {"t": "Nov 21 10:00:02 pve1 sshd[999]: error: something failed",
                 "PRIORITY": 3, "MESSAGE": "error: something failed"}]))
        m = re.match(r"^/api2/json/nodes/pve1/tasks/(UPID:.+)/status$", p)
        if m:
            return self._send(200, env({
                "upid": UPID, "node": "pve1", "type": "vzdump", "user": "root@pam",
                "starttime": 1730000000, "endtime": 1730000600, "status": "OK"}))
        m = re.match(r"^/api2/json/nodes/pve1/tasks/(UPID:.+)/log$", p)
        if m:
            return self._send(200, env([
                {"n": 1, "t": "INFO: starting new backup job"},
                {"n": 2, "t": "INFO: 10% (1.0 GiB of 10.0 GiB) in 10s"}]))
        return self._send(404, env(None))

    def POST(self):
        p, body = self._path(), self._body()
        form = dict(urllib.parse.parse_qsl(body))
        if p == "/api2/json/access/ticket":
            if form.get("username") == "root@pam" and form.get("password") == "secret123":
                return self._send(200, env({"ticket": VALID_TICKET,
                                            "CSRFPreventionToken": "CSRFToken:abcd1234",
                                            "username": "root@pam",
                                            "cap": {"vms": ["VM.PowerMgmt"]}}))
            return self._send(401, json.dumps({"data": None}).encode())
        if not self._authed():
            return self._send(401, env(None))
        if not self._csrf_ok():
            return self._send(403, env(None))
        m = re.match(r"^/api2/json/nodes/pve1/(qemu|lxc)/(\d+)/status/(start|stop|shutdown|reboot)$", p)
        if m:
            return self._send(200, env(UPID))
        if p == "/api2/json/nodes/pve1/qemu/101/migrate":
            if form.get("target") and form.get("online") in ("0", "1"):
                return self._send(200, env(UPID))
            return self._send(400, env(None))
        if p == "/api2/json/nodes/pve1/qemu/101/snapshot":
            if form.get("snapname"):
                return self._send(200, env(UPID))
            return self._send(400, env(None))
        m = re.match(r"^/api2/json/nodes/pve1/qemu/101/snapshot/(.+)/rollcurrent$", p)
        if m:
            return self._send(200, env(UPID))
        if p == "/api2/json/nodes/pve1/vzdump":
            if form.get("vmid") and form.get("storage") and form.get("mode"):
                return self._send(200, env(UPID))
            return self._send(400, env(None))
        if p == "/api2/json/nodes/pve1/qemu/101/termproxy":
            return self._send(200, env({"port": 5900, "ticket": "PVEVNC:abc",
                                        "upid": UPID}))
        m = re.match(r"^/api2/json/nodes/pve1/services/([\w.@-]+)/(start|stop|restart|reload)$", p)
        if m:
            return self._send(200, env(UPID))
        if p in ("/api2/json/nodes/pve1/reboot", "/api2/json/nodes/pve1/shutdown",
                 "/api2/json/nodes/pve1/startall", "/api2/json/nodes/pve1/stopall",
                 "/api2/json/nodes/pve1/suspendall", "/api2/json/nodes/pve1/hibernateall"):
            return self._send(200, env(UPID))
        return self._send(404, env(None))

    def DELETE(self):
        p = self._path()
        if not self._authed():
            return self._send(401, env(None))
        if not self._csrf_ok():
            return self._send(403, env(None))
        m = re.match(r"^/api2/json/nodes/pve1/qemu/101/snapshot/(.+)$", p)
        if m and m.group(1) != "current":
            return self._send(200, env(None))
        return self._send(404, env(None))

    # route dispatch (route names cannot start with http)
    def do_GET(self):
        self.route_get()

    def route_get(self):
        self.GET()

    def do_POST(self):
        self.POST()

    def do_DELETE(self):
        self.DELETE()


if __name__ == "__main__":
    # 绑定 0.0.0.0：冒烟扫描测试需要通过本机局域网 IP 发现该服务
    httpd = http.server.ThreadingHTTPServer(("0.0.0.0", 8006), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain("/tmp/pvesmoke/cert/cert.pem", "/tmp/pvesmoke/cert/key.pem")
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print("mock PVE listening on https://127.0.0.1:8006", flush=True)
    httpd.serve_forever()
