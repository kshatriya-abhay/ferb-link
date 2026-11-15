"""A stand-in for the router, for server/test.sh: logs each request's path to argv[1] and answers
as an idle router with its model unloaded. Writes its port to argv[2] once listening."""
import http.server, json, sys

LOG, PORT_FILE = sys.argv[1], sys.argv[2]


class Router(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with open(LOG, "a") as log:
            log.write(self.path + "\n")
        if self.path == "/health":
            self.reply(200, {"status": "ok"})
        elif self.path == "/v1/models":
            self.reply(200, {"data": [{"id": "stub-model", "status": {"value": "unloaded"}}]})
        elif self.path.startswith("/props") and "autoload=false" in self.path:
            self.reply(400, {"error": {"message": "model is not loaded"}})
        else:
            self.reply(200, {"modalities": {"vision": True, "audio": True}, "loaded_by": "this request"})

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


server = http.server.HTTPServer(("127.0.0.1", 0), Router)
with open(PORT_FILE, "w") as f:
    f.write(str(server.server_port))
server.serve_forever()
