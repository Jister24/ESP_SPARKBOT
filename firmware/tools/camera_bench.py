"""测量 /stream 的真实帧率、帧间隔分布与码率。

用法: python3 camera_bench.py [时长秒=20] [设备IP]
"""
import socket, time, sys

DUR = float(sys.argv[1]) if len(sys.argv) > 1 else 20.0
HOST = sys.argv[2] if len(sys.argv) > 2 else "192.168.31.212"
PORT = 8081

s = socket.create_connection((HOST, PORT), timeout=5)
s.sendall(b"GET /stream HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n" % HOST.encode())
s.settimeout(10)

buf = b""
header_done = False
t0 = None
sizes = []
stamps = []
deadline = time.time() + DUR + 15
while time.time() < deadline:
    try:
        chunk = s.recv(65536)
    except socket.timeout:
        break
    if not chunk:
        break
    buf += chunk
    if not header_done:
        i = buf.find(b"\r\n\r\n")
        if i < 0:
            continue
        buf = buf[i+4:]
        header_done = True
        t0 = time.time()
    while True:
        a = buf.find(b"\xff\xd8")
        b = buf.find(b"\xff\xd9", a + 2 if a >= 0 else 0)
        if a < 0 or b < 0:
            break
        sizes.append(b + 2 - a)
        stamps.append(time.time())
        buf = buf[b+2:]
    if t0 and time.time() - t0 >= DUR:
        break
s.close()

if len(stamps) < 2:
    print(f"FAIL 只拿到 {len(stamps)} 帧")
    sys.exit(1)

elapsed = stamps[-1] - stamps[0]
fps = (len(stamps) - 1) / elapsed
gaps = [(stamps[i+1] - stamps[i]) * 1000 for i in range(len(stamps) - 1)]
gaps_sorted = sorted(gaps)
def pct(p):
    return gaps_sorted[min(len(gaps_sorted)-1, int(len(gaps_sorted)*p))]

print(f"时长       {elapsed:.1f}s")
print(f"帧数       {len(stamps)}")
print(f"平均 fps   {fps:.2f}")
print(f"帧间隔ms   min {min(gaps):.0f} / p50 {pct(0.5):.0f} / p90 {pct(0.9):.0f} / max {max(gaps):.0f}")
print(f"单帧字节   avg {sum(sizes)//len(sizes)}  min {min(sizes)}  max {max(sizes)}")
print(f"码率       {sum(sizes)*8/elapsed/1000:.0f} kbps")
