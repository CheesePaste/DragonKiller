import socket
import json


class MCProtocol:
    def __init__(self, host="127.0.0.1", port=5670):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.sock.settimeout(30.0)
        self.sock.connect((host, port))
        self._recv_buf = b""

    def send(self, msg: dict):
        data = (json.dumps(msg) + "\n").encode("utf-8")
        self.sock.sendall(data)

    def recv(self) -> dict:
        while b"\n" not in self._recv_buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise ConnectionError("Connection closed")
            self._recv_buf += chunk
        line, self._recv_buf = self._recv_buf.split(b"\n", 1)
        return json.loads(line.decode("utf-8"))

    def reset(self) -> dict:
        self.send({"type": "reset"})
        return self.recv()

    def step(self, action: list) -> dict:
        self.send({"type": "step", "action": action})
        return self.recv()

    def close(self):
        self.send({"type": "close"})
        self.sock.close()
