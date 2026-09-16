// Generate PWA PNG icons (192/512) without image libraries: solid rounded background + simple monitor glyph.
import { deflateSync } from "node:zlib";
import { writeFileSync } from "node:fs";

function crc32(buf) {
  let c, crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function png(size, pixel) {
  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      const [r, g, b, a] = pixel(x, y);
      const o = y * (size * 4 + 1) + 1 + x * 4;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b; raw[o + 3] = a;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk("IHDR", ihdr), chunk("IDAT", deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}
function icon(size) {
  const bg = [15, 23, 42], fg = [56, 189, 248], dot = [34, 197, 94];
  const r = size * 0.22;
  const inRounded = (x, y) => {
    const cx = Math.min(Math.max(x, r), size - r), cy = Math.min(Math.max(y, r), size - r);
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r;
  };
  const s = size / 64;
  const stroke = 4 * s;
  const rect = { x: 12 * s, y: 14 * s, w: 40 * s, h: 26 * s };
  const onRectEdge = (x, y) => {
    const inOuter = x >= rect.x - stroke / 2 && x <= rect.x + rect.w + stroke / 2 && y >= rect.y - stroke / 2 && y <= rect.y + rect.h + stroke / 2;
    const inInner = x >= rect.x + stroke / 2 && x <= rect.x + rect.w - stroke / 2 && y >= rect.y + stroke / 2 && y <= rect.y + rect.h - stroke / 2;
    return inOuter && !inInner;
  };
  const onStand = (x, y) => (Math.abs(x - 32 * s) <= stroke / 2 && y >= 40 * s && y <= 50 * s) || (Math.abs(y - 50 * s) <= stroke / 2 && x >= 24 * s && x <= 40 * s);
  const onDot = (x, y) => (x - 46 * s) ** 2 + (y - 20 * s) ** 2 <= (4 * s) ** 2;
  return png(size, (x, y) => {
    if (!inRounded(x + 0.5, y + 0.5)) return [0, 0, 0, 0];
    if (onDot(x, y)) return [...dot, 255];
    if (onRectEdge(x, y) || onStand(x, y)) return [...fg, 255];
    return [...bg, 255];
  });
}
for (const size of [192, 512]) writeFileSync(new URL(`../apps/web/public/icon-${size}.png`, import.meta.url), icon(size));
console.log("icons written");
