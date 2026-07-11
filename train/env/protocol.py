import socket
import json


class MCProtocol:
    def __init__(self, host="127.0.0.1", port=5670):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(30.0)
        self.sock.connect((host, port))

    def send(self, msg: dict):
        data = (json.dumps(msg) + "\n").encode("utf-8")
        self.sock.sendall(data)

    def recv(self) -> dict:
        buf = b""
        while not buf.endswith(b"\n"):
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("Connection closed")
            buf += chunk
        return json.loads(buf.rstrip(b"\n"))

    def reset(self) -> dict:
        self.send({"type": "reset"})
        return self.recv()

    def step(self, action: int) -> dict:
        self.send({"type": "step", "action": action})
        return self.recv()

    def close(self):
        self.send({"type": "close"})
        self.sock.close()
