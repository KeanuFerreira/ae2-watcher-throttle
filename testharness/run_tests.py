#!/usr/bin/env python3
"""Tests AE2 Watcher Throttle on a real Forge dedicated server.

Usage: run_tests.py <server_dir> <throttle_jar_or_none>

<server_dir> must contain a Forge 1.20.1-47.4.20 server with AE2 15.4.10 and GuideME in mods/.
Runs one session with watcherUpdateInterval = 1 (AE2's behaviour) and one with 10.
"""
import os, queue, re, shutil, subprocess, sys, threading, time

HERE = os.path.dirname(os.path.abspath(__file__))
WATCHED, PLAIN = "0 -60 0", "0 -60 8"
results = []


def check(label, ok, detail=""):
    results.append((label, ok))
    print(("PASS " if ok else "FAIL ") + label + (("  [" + detail + "]") if detail else ""), flush=True)


class Server:
    def __init__(self, path):
        self.path, self.lines = path, queue.Queue()

    def start(self):
        self.proc = subprocess.Popen(["bash", "run.sh", "nogui"], cwd=self.path, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=self._read, daemon=True).start()
        self.wait(lambda l: "Done (" in l and "For help" in l, 600)

    def _read(self):
        with open(os.path.join(self.path, "test-console.log"), "a") as log:
            for line in self.proc.stdout:
                log.write(line); log.flush(); self.lines.put(line.rstrip("\n"))

    def wait(self, pred, timeout=60):
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                if self.proc.poll() is not None:
                    raise RuntimeError("server exited")
                continue
            if "ATT ERROR" in line:
                raise RuntimeError(line)
            if pred(line):
                return line
        raise TimeoutError("timed out")

    def send(self, cmd):
        self.proc.stdin.write(cmd + "\n"); self.proc.stdin.flush()

    def att(self, cmd, expect, timeout=60):
        self.send("att " + cmd)
        return self.wait(lambda l: ("ATT " + expect) in l, timeout).split("ATT ", 1)[1]

    def stop(self):
        self.send("stop"); self.proc.wait(timeout=300)

    def kill(self):
        if getattr(self, "proc", None) and self.proc.poll() is None:
            self.proc.kill(); self.proc.wait()


def num(line, key):
    return float(re.search(re.escape(key) + r"=(-?[\d.]+)", line).group(1))


def prepare(server_dir, jar, interval):
    mods = os.path.join(server_dir, "mods")
    for f in os.listdir(mods):
        if f.startswith(("ae2watcherthrottle", "ae2throttletest")):
            os.remove(os.path.join(mods, f))
    if jar != "none":
        shutil.copy(jar, mods)
    libs = os.path.join(HERE, "build", "libs")
    shutil.copy(os.path.join(libs, [f for f in os.listdir(libs) if f.startswith("ae2throttletest")][0]), mods)
    shutil.rmtree(os.path.join(server_dir, "world"), ignore_errors=True)
    open(os.path.join(server_dir, "eula.txt"), "w").write("eula=true\n")
    open(os.path.join(server_dir, "server.properties"), "w").write(
        "online-mode=false\nlevel-type=minecraft\\:flat\ngenerate-structures=false\nspawn-protection=0\n"
        "view-distance=4\nsimulation-distance=4\nspawn-monsters=false\nspawn-animals=false\n"
        "max-tick-time=-1\nserver-port=25599\n")
    open(os.path.join(server_dir, "user_jvm_args.txt"), "w").write("-Xms2G\n-Xmx2G\n")
    os.makedirs(os.path.join(server_dir, "config"), exist_ok=True)
    open(os.path.join(server_dir, "config", "ae2watcherthrottle-common.toml"), "w").write(
        "watcherUpdateInterval = %d\n" % interval)


def session(server_dir, jar, interval, label):
    prepare(server_dir, jar, interval)
    s = Server(server_dir)
    SERVERS.append(s)
    s.start()
    s.send("forceload add -16 -16 16 16"); time.sleep(1)
    s.att("setup %s 1" % WATCHED, "setup")
    s.att("setup %s 0" % PLAIN, "setup")
    time.sleep(3)
    dump = s.att("dump " + WATCHED, "dump")
    check("%s: emitter is on the drive's network" % label, num(dump, "nodes") >= 4, dump)
    rate = num(s.att("rate 200", "rate", 60), "perTick")
    on, off = [], []
    for _ in range(5):
        s.send("att latency " + WATCHED)
        on.append(num(s.wait(lambda l: "ATT latency on" in l, 60), "ticks"))
        off.append(num(s.wait(lambda l: "ATT latency off" in l, 60), "ticks"))
    fresh = s.att("fresh " + WATCHED, "fresh")
    s.send("setblock -1 -60 0 air")  # remove the emitter's cable bus: no watchers left
    time.sleep(2)
    rate_none = num(s.att("rate 200", "rate", 60), "perTick")
    s.stop()
    print("%s: rebuilds/tick with watcher %.3f, without %.3f; emitter on %s off %s ticks; %s"
          % (label, rate, rate_none, on, off, fresh))
    return rate, rate_none, on, off, fresh


SERVERS = []


def main():
    server_dir, jar = sys.argv[1], sys.argv[2]
    try:
        r1, n1, on1, off1, f1 = session(server_dir, jar, 1, "interval 1")
        check("interval 1: AE2 behaviour, one rebuild per tick", 0.95 <= r1 <= 1.05, "%.3f" % r1)
        check("interval 1: emitter reacts within 1-2 ticks", max(on1 + off1) <= 2, "%s %s" % (on1, off1))
        r10, n10, on10, off10, f10 = session(server_dir, jar, 10, "interval 10")
        check("interval 10: one rebuild per 10 ticks", 0.09 <= r10 <= 0.11, "%.3f" % r10)
        check("interval 10: emitter reacts within 10 ticks", max(on10 + off10) <= 11, "%s %s" % (on10, off10))
        check("interval 10: cached inventory fresh on demand", num(f10, "after") == num(f10, "before") + 5, f10)
        check("no watchers: no per-tick rebuilds (both)", n1 < 0.01 and n10 < 0.01, "%.3f %.3f" % (n1, n10))
    finally:
        for s in SERVERS:
            s.kill()
    failed = [l for l, ok in results if not ok]
    print("\n%d checks, %d failed" % (len(results), len(failed)))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
