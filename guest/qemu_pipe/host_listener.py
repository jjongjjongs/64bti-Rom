#!/usr/bin/env python3
"""스모크 부팅용 호스트측 파이프 청취기.

앱에서는 libemugl_probe.so 가 QEMU 가 열어둔 유닉스 소켓들에 붙어서, 게스트가 보낸
"pipe:<서비스>" 헤더를 읽고 해당 서비스로 연결해 준다. CI 에는 그 렌더러가 없으므로
소켓에 아무도 붙지 않고, 그래서 게스트가 파이프를 열려고 시도하는지조차 알 수 없었다.

이 스크립트는 그 자리를 대신해 "듣기만" 한다. GLES 프로토콜은 구현하지 않는다.
확인하려는 것은 딱 하나 — 게스트에서 나온 바이트가 호스트 소켓까지 실제로 도달하는가,
그리고 그 첫 바이트가 "pipe:opengles" 인가. 전송로 전체를 GLES 를 빼고 검증하는 셈이다.

사용법: host_listener.py <소켓경로> [<소켓경로> ...]
"""
import os
import socket
import sys
import threading
import time

CONNECT_TIMEOUT_S = 240
RECONNECT_DELAY_S = 0.5


def log(msg: str) -> None:
    print(msg, flush=True)


def watch(path: str) -> None:
    name = os.path.basename(path)

    # QEMU 가 소켓을 만들기 전에 시작될 수 있다. 붙을 때까지 기다린다.
    deadline = time.monotonic() + CONNECT_TIMEOUT_S
    sock = None
    while time.monotonic() < deadline:
        try:
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            s.connect(path)
            sock = s
            break
        except OSError:
            time.sleep(RECONNECT_DELAY_S)
    if sock is None:
        log(f"[{name}] 연결 실패 (소켓이 생기지 않음)")
        return

    log(f"[{name}] 연결됨")
    total = 0
    header_done = False
    try:
        while True:
            data = sock.recv(65536)
            if not data:
                log(f"[{name}] 게스트가 닫음 (총 {total} 바이트)")
                return
            total += len(data)
            if not header_done:
                # goldfish pipe 의 첫 쓰기는 NUL 로 끝나는 서비스 이름이다.
                head = data.split(b"\x00", 1)[0]
                try:
                    text = head.decode("ascii")
                except UnicodeDecodeError:
                    text = repr(head[:32])
                log(f"[{name}] *** 서비스 헤더: {text!r} ({len(data)} 바이트 수신) ***")
                header_done = True
            elif total < 4096:
                log(f"[{name}] +{len(data)} 바이트 (누적 {total})")
    except OSError as exc:
        log(f"[{name}] 오류: {exc} (총 {total} 바이트)")


def main() -> int:
    paths = sys.argv[1:]
    if not paths:
        log("소켓 경로를 하나 이상 넘겨야 합니다")
        return 2
    threads = [threading.Thread(target=watch, args=(p,), daemon=True) for p in paths]
    for t in threads:
        t.start()
    # 스모크 부팅이 끝나면 부모가 죽인다. 그때까지 그냥 살아 있는다.
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    sys.exit(main())
