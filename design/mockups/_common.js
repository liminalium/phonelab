// Deterministic fake signals so every render looks the same.
function wave(n, f, amp, noise, seed) {
  let s = seed || 1, out = [];
  const rnd = () => { s = (s * 16807) % 2147483647; return s / 2147483647 - 0.5; };
  for (let i = 0; i < n; i++) out.push(Math.sin(i * f) * amp + Math.sin(i * f * 3.1) * amp * 0.3 + rnd() * noise);
  return out;
}
function path(vals, w, h, lo, hi) {
  return vals.map((v, i) => `${i ? 'L' : 'M'}${(i / (vals.length - 1) * w).toFixed(1)},${(h - (v - lo) / (hi - lo) * h).toFixed(1)}`).join(' ');
}
function spark(el, vals, color, lo, hi, width) {
  const w = el.clientWidth || 160, h = el.clientHeight || 36;
  el.innerHTML = `<svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><path d="${path(vals, w, h, lo, hi)}" fill="none" stroke="${color}" stroke-width="${width || 1.6}" stroke-linejoin="round"/></svg>`;
}
function bars(el, n, color, seed) {
  const v = wave(n, 0.35, 0.5, 0.6, seed).map((x, i) => Math.max(0.08, Math.min(1, 0.55 + x - i / n * 0.35)));
  el.innerHTML = v.map(x => `<i style="height:${(x * 100).toFixed(0)}%;background:${color}"></i>`).join('');
}
