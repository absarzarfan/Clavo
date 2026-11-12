import os, re, sys, subprocess

# Pemakaian: python pantau.py [FILE_LOG] [JUMLAH_EPOCH]
# Membaca log pelatihan Model Maker dan menampilkan ringkasan progres yang mudah dibaca.
log = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else '~/clavo/results/train_apip_r1.log')
total = int(sys.argv[2]) if len(sys.argv) > 2 else 20
text = open(log, errors='ignore').read()
lines = [l.strip() for l in re.split(r'[\r\n]+', text) if l.strip()]

cfg = next((l for l in lines if l.startswith('KONFIGURASI')), '')
epoch, prog, done = 0, None, []
for l in lines:
    m = re.match(r'^Epoch (\d+)/(\d+)', l)
    if m:
        epoch, total = int(m.group(1)), int(m.group(2)); prog = None
    m = re.match(r'^(\d+)/(\d+) \[[=>.]*\]', l)
    if m:
        prog = l
        f = re.match(r'^(\d+)/(\d+) \[=+\] - (\d+)s (\d+)ms/step - det_loss: ([\d.]+).*?- val_loss: ([\d.]+)', l)
        if f:
            done.append((epoch, int(f.group(3)), float(f.group(5)), float(f.group(6))))

running = subprocess.run(['pgrep', '-f', r'^([^ ]*/)?python[^ ]* .*my_script_apip\.py'], capture_output=True, text=True).stdout.strip() != ''
finished = ("'AP'" in text) or ('AP50' in text)
print(cfg[:150] if cfg else '(belum ada baris KONFIGURASI di log)')
print('-' * 70)
for e, sec, dl, vl in done:
    print(f'  epoch {e:2d}/{total}: {sec:4d} detik | det_loss {dl:.4f} | val_loss {vl:.4f}')
if prog and not (done and done[-1][0] == epoch and epoch == total):
    m = re.match(r'^(\d+)/(\d+) \[[=>.]*\](?: - ETA: (\S+))?', prog)
    if m and (not done or done[-1][0] != epoch):
        pct = 100 * int(m.group(1)) / int(m.group(2))
        print(f'  epoch {epoch:2d}/{total}: BERJALAN, langkah {m.group(1)}/{m.group(2)} ({pct:.0f}%), sisa ±{m.group(3) or "?"}')
if done:
    avg = sum(s for _, s, _, _ in done) / len(done)
    sisa_epoch = total - len(done) - (1 if (prog and (not done or done[-1][0] != epoch)) else 0)
    print('-' * 70)
    print(f'rata-rata {avg:.0f} detik/epoch | epoch tersisa penuh: {max(sisa_epoch, 0)} | perkiraan sisa ±{max(sisa_epoch, 0) * avg / 60:.0f} menit (belum termasuk ekspor dan evaluasi)')
status = 'SELESAI (evaluasi tercetak di log)' if finished else ('BERJALAN' if running else 'TIDAK BERJALAN (dihentikan Ctrl+C atau error; cek ujung log)')
print('STATUS:', status)
