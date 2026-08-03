import {
  PRIVATE_ALBUM_MAX_IMAGE_BYTES,
  validatePrivateAlbumImage,
} from "./privateAlbumImage.ts";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEquals<T>(actual: T, expected: T, message: string): void {
  if (actual !== expected) {
    throw new Error(`${message}: expected ${expected}, received ${actual}`);
  }
}

function fromBase64(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (character) => character.charCodeAt(0));
}

const validPng = fromBase64(
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
);

const validJpeg = fromBase64(
  "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoM" +
    "DAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsN" +
    "FBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAAR" +
    "CAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA" +
    "AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkK" +
    "FhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWG" +
    "h4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl" +
    "5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA" +
    "AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYk" +
    "NOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOE" +
    "hYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk" +
    "5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD8qqKKKAP/2Q==",
);

const validWebp = fromBase64(
  "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEAAUAmJaQAA3AA/v89",
);

function assertValid(
  bytes: Uint8Array,
  mimeType: string,
  width: number,
  height: number,
): void {
  const result = validatePrivateAlbumImage(bytes, mimeType);
  assert(result.valid, `${mimeType} should be valid`);
  assertEquals(result.mimeType, mimeType, "validated MIME");
  assertEquals(result.width, width, "validated width");
  assertEquals(result.height, height, "validated height");
}

function crc32(bytes: Uint8Array): number {
  let value = 0xffffffff;
  for (const byte of bytes) {
    value ^= byte;
    for (let bit = 0; bit < 8; bit++) {
      value = (value & 1) === 1 ? (0xedb88320 ^ (value >>> 1)) : value >>> 1;
    }
  }
  return (value ^ 0xffffffff) >>> 0;
}

function uint32BigEndian(value: number): number[] {
  return [
    (value >>> 24) & 0xff,
    (value >>> 16) & 0xff,
    (value >>> 8) & 0xff,
    value & 0xff,
  ];
}

function pngChunk(type: string, data: Uint8Array): Uint8Array {
  const typeBytes = Uint8Array.from(type, (value) => value.charCodeAt(0));
  const crcInput = new Uint8Array(typeBytes.length + data.length);
  crcInput.set(typeBytes);
  crcInput.set(data, typeBytes.length);
  return Uint8Array.from([
    ...uint32BigEndian(data.length),
    ...typeBytes,
    ...data,
    ...uint32BigEndian(crc32(crcInput)),
  ]);
}

function pngWithDimensions(
  width: number,
  height: number,
  metadataBytes = 0,
): Uint8Array {
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  const ihdr = pngChunk(
    "IHDR",
    Uint8Array.from([
      ...uint32BigEndian(width),
      ...uint32BigEndian(height),
      8,
      6,
      0,
      0,
      0,
    ]),
  );
  const metadata = metadataBytes > 0
    ? pngChunk("tEXt", new Uint8Array(metadataBytes))
    : new Uint8Array();
  const idat = pngChunk(
    "IDAT",
    Uint8Array.from([
      0x78,
      0xda,
      0x63,
      0x64,
      0xf8,
      0x0f,
      0x00,
      0x01,
      0x05,
      0x01,
      0x01,
    ]),
  );
  const iend = pngChunk("IEND", new Uint8Array());
  return Uint8Array.from([
    ...signature,
    ...ihdr,
    ...metadata,
    ...idat,
    ...iend,
  ]);
}

function jpegWithDimensions(width: number, height: number): Uint8Array {
  const result = validJpeg.slice();
  for (let offset = 2; offset + 8 < result.length; offset++) {
    if (result[offset] === 0xff && result[offset + 1] === 0xc0) {
      result[offset + 5] = (height >>> 8) & 0xff;
      result[offset + 6] = height & 0xff;
      result[offset + 7] = (width >>> 8) & 0xff;
      result[offset + 8] = width & 0xff;
      return result;
    }
  }
  throw new Error("synthetic JPEG fixture has no SOF0 marker");
}

function webpWithDimensions(width: number, height: number): Uint8Array {
  const result = validWebp.slice();
  const widthBits = width & 0x3fff;
  const heightBits = height & 0x3fff;
  result[26] = widthBits & 0xff;
  result[27] = (widthBits >>> 8) & 0x3f;
  result[28] = heightBits & 0xff;
  result[29] = (heightBits >>> 8) & 0x3f;
  return result;
}

Deno.test("valid JPEG PNG and WebP containers expose safe dimensions", () => {
  assertValid(validJpeg, "image/jpeg", 1, 1);
  assertValid(validPng, "image/png", 1, 1);
  assertValid(validWebp, "image/webp", 1, 1);
});

Deno.test("declared MIME must match the image magic and container", () => {
  const cases: Array<[Uint8Array, string]> = [
    [validPng, "image/jpeg"],
    [validPng, "image/webp"],
    [validJpeg, "image/png"],
    [validWebp, "image/jpeg"],
  ];
  for (const [bytes, mimeType] of cases) {
    assert(
      !validatePrivateAlbumImage(bytes, mimeType).valid,
      `${mimeType} mismatch must fail`,
    );
  }
});

Deno.test("zero and oversized payloads fail before format parsing", () => {
  assert(
    !validatePrivateAlbumImage(new Uint8Array(), "image/png").valid,
    "empty image",
  );
  assert(
    !validatePrivateAlbumImage(
      new Uint8Array(PRIVATE_ALBUM_MAX_IMAGE_BYTES + 1),
      "image/png",
    ).valid,
    "oversized image",
  );
});

Deno.test("huge dimensions in JPEG PNG and WebP are rejected", () => {
  for (
    const [bytes, mimeType] of [
      [jpegWithDimensions(4097, 1), "image/jpeg"],
      [pngWithDimensions(4097, 1), "image/png"],
      [webpWithDimensions(4097, 1), "image/webp"],
    ] as Array<[Uint8Array, string]>
  ) {
    assert(
      !validatePrivateAlbumImage(bytes, mimeType).valid,
      `${mimeType} oversized side`,
    );
  }
});

Deno.test("sixteen megapixel budget is independent from side limits", () => {
  assert(
    !validatePrivateAlbumImage(
      pngWithDimensions(4096, 4096),
      "image/png",
    ).valid,
    "pixel budget",
  );
  assert(
    validatePrivateAlbumImage(
      pngWithDimensions(4000, 4000),
      "image/png",
    ).valid,
    "sixteen megapixels remains accepted",
  );
});

Deno.test("truncated JPEG PNG and WebP containers fail closed", () => {
  for (
    const [bytes, mimeType] of [
      [validJpeg, "image/jpeg"],
      [validPng, "image/png"],
      [validWebp, "image/webp"],
    ] as Array<[Uint8Array, string]>
  ) {
    const truncated = bytes.slice(0, bytes.length - 1);
    assert(
      !validatePrivateAlbumImage(truncated, mimeType).valid,
      `${mimeType} truncation`,
    );
  }
});

Deno.test("PNG CRC corruption is rejected", () => {
  const corrupted = validPng.slice();
  corrupted[corrupted.length - 8] ^= 0x01;
  assert(
    !validatePrivateAlbumImage(corrupted, "image/png").valid,
    "corrupt PNG",
  );
});

Deno.test("metadata aggregate over one MiB is rejected without parsing it", () => {
  const bomb = pngWithDimensions(1, 1, 1024 * 1024 + 1);
  assert(
    !validatePrivateAlbumImage(bomb, "image/png").valid,
    "metadata bomb",
  );
});
