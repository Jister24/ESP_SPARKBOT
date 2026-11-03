"""检查 MJPEG 流里有没有坏帧（尺寸异常），并统计帧大小分布。

用法: python3 camera_frames.py [时长秒=12] [设备IP]
"""
import socket, sys, time

DUR = float(sys.argv[1]) if len(sys.argv) > 1 else 12.0
HOST = sys.argv[2] if len(sys.argv) > 2 else "192.168.31.212"
s=socket.create_connection((HOST,8081),timeout=5)
s.sendall(b"GET /stream HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
s.settimeout(6)
buf=b""; hdr=False; t0=None; sizes=[]
while True:
    try: c=s.recv(65536)
    except Exception: break
    if not c: break
    buf+=c
    if not hdr:
        i=buf.find(b"\r\n\r\n")
        if i<0: continue
        buf=buf[i+4:]; hdr=True; t0=time.time()
    while True:
        a=buf.find(b"\xff\xd8")
        b=buf.find(b"\xff\xd9", a+2 if a>=0 else 0)
        if a<0 or b<0: break
        sizes.append(b+2-a); buf=buf[b+2:]
    if t0 and time.time()-t0>=DUR: break
s.close()

small=[x for x in sizes if x<8000]
print(f"总帧 {len(sizes)}  小帧(<8KB) {len(small)}  占比 {len(small)*100//max(1,len(sizes))}%")
print(f"尺寸分布: <5KB {sum(1 for x in sizes if x<5000)} | 5-15KB {sum(1 for x in sizes if 5000<=x<15000)} | >15KB {sum(1 for x in sizes if x>=15000)}")
print(f"前 5 帧尺寸: {sizes[:5]}")
print(f"后 5 帧尺寸: {sizes[-5:]}")
