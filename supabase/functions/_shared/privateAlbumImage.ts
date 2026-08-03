export const PRIVATE_ALBUM_MIN_IMAGE_BYTES = 1;
export const PRIVATE_ALBUM_MAX_IMAGE_BYTES = 5 * 1024 * 1024;
export const PRIVATE_ALBUM_MAX_IMAGE_DIMENSION = 4096;
export const PRIVATE_ALBUM_MAX_IMAGE_PIXELS = 16_000_000;

const MAX_METADATA_BYTES = 1024 * 1024;
const MAX_CONTAINER_ELEMENTS = 4096;

export type PrivateAlbumImageMimeType =
  | "image/jpeg"
  | "image/png"
  | "image/webp";

export type PrivateAlbumImageValidation =
  | {
    valid: true;
    mimeType: PrivateAlbumImageMimeType;
    width: number;
    height: number;
  }
  | { valid: false };

type Dimensions = { width: number; height: number };

const INVALID: PrivateAlbumImageValidation = { valid: false };

function hasSafeDimensions(dimensions: Dimensions): boolean {
  const { width, height } = dimensions;
  return Number.isInteger(width) && Number.isInteger(height) && width > 0 &&
    height > 0 && width <= PRIVATE_ALBUM_MAX_IMAGE_DIMENSION &&
    height <= PRIVATE_ALBUM_MAX_IMAGE_DIMENSION &&
    width * height <= PRIVATE_ALBUM_MAX_IMAGE_PIXELS;
}

function readUint16BigEndian(bytes: Uint8Array, offset: number): number {
  return (bytes[offset] << 8) | bytes[offset + 1];
}

function readUint16LittleEndian(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8);
}

function readUint24LittleEndian(bytes: Uint8Array, offset: number): number {
  return bytes[offset] | (bytes[offset + 1] << 8) |
    (bytes[offset + 2] << 16);
}

function readUint32BigEndian(bytes: Uint8Array, offset: number): number {
  return (
    bytes[offset] * 0x1000000 +
    (bytes[offset + 1] << 16) +
    (bytes[offset + 2] << 8) +
    bytes[offset + 3]
  ) >>> 0;
}

function readUint32LittleEndian(bytes: Uint8Array, offset: number): number {
  return (
    bytes[offset] +
    bytes[offset + 1] * 0x100 +
    bytes[offset + 2] * 0x10000 +
    bytes[offset + 3] * 0x1000000
  ) >>> 0;
}

function fourCc(bytes: Uint8Array, offset: number): string {
  return String.fromCharCode(
    bytes[offset],
    bytes[offset + 1],
    bytes[offset + 2],
    bytes[offset + 3],
  );
}

function isAsciiLetter(value: number): boolean {
  return (value >= 0x41 && value <= 0x5a) ||
    (value >= 0x61 && value <= 0x7a);
}

const CRC32_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let index = 0; index < table.length; index++) {
    let value = index;
    for (let bit = 0; bit < 8; bit++) {
      value = (value & 1) === 1 ? (0xedb88320 ^ (value >>> 1)) : value >>> 1;
    }
    table[index] = value >>> 0;
  }
  return table;
})();

function crc32(bytes: Uint8Array, start: number, end: number): number {
  let value = 0xffffffff;
  for (let index = start; index < end; index++) {
    value = CRC32_TABLE[(value ^ bytes[index]) & 0xff] ^ (value >>> 8);
  }
  return (value ^ 0xffffffff) >>> 0;
}

function validPngBitDepth(bitDepth: number, colorType: number): boolean {
  switch (colorType) {
    case 0:
      return bitDepth === 1 || bitDepth === 2 || bitDepth === 4 ||
        bitDepth === 8 || bitDepth === 16;
    case 2:
    case 4:
    case 6:
      return bitDepth === 8 || bitDepth === 16;
    case 3:
      return bitDepth === 1 || bitDepth === 2 || bitDepth === 4 ||
        bitDepth === 8;
    default:
      return false;
  }
}

function parsePng(bytes: Uint8Array): Dimensions | null {
  const signature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (
    bytes.length < signature.length ||
    signature.some((value, index) => bytes[index] !== value)
  ) {
    return null;
  }

  let offset = signature.length;
  let elements = 0;
  let metadataBytes = 0;
  let dimensions: Dimensions | null = null;
  let colorType = -1;
  let sawPalette = false;
  let sawImageData = false;
  let imageDataEnded = false;
  let imageDataBytes = 0;
  const zlibPrefix: number[] = [];

  while (offset < bytes.length) {
    if (++elements > MAX_CONTAINER_ELEMENTS || bytes.length - offset < 12) {
      return null;
    }

    const dataLength = readUint32BigEndian(bytes, offset);
    const typeOffset = offset + 4;
    const dataOffset = offset + 8;
    const dataEnd = dataOffset + dataLength;
    const chunkEnd = dataEnd + 4;
    if (
      dataEnd < dataOffset || chunkEnd < dataEnd || chunkEnd > bytes.length ||
      !isAsciiLetter(bytes[typeOffset]) ||
      !isAsciiLetter(bytes[typeOffset + 1]) ||
      !isAsciiLetter(bytes[typeOffset + 2]) ||
      !isAsciiLetter(bytes[typeOffset + 3])
    ) {
      return null;
    }

    if (
      crc32(bytes, typeOffset, dataEnd) !==
        readUint32BigEndian(bytes, dataEnd)
    ) {
      return null;
    }

    const chunkType = fourCc(bytes, typeOffset);
    if (elements === 1 && chunkType !== "IHDR") return null;

    switch (chunkType) {
      case "IHDR": {
        if (dimensions || dataLength !== 13) return null;
        const width = readUint32BigEndian(bytes, dataOffset);
        const height = readUint32BigEndian(bytes, dataOffset + 4);
        const bitDepth = bytes[dataOffset + 8];
        colorType = bytes[dataOffset + 9];
        if (
          !hasSafeDimensions({ width, height }) ||
          !validPngBitDepth(bitDepth, colorType) ||
          bytes[dataOffset + 10] !== 0 || bytes[dataOffset + 11] !== 0 ||
          bytes[dataOffset + 12] > 1
        ) {
          return null;
        }
        dimensions = { width, height };
        break;
      }
      case "PLTE":
        if (
          !dimensions || sawPalette || sawImageData || dataLength === 0 ||
          dataLength > 768 || dataLength % 3 !== 0 ||
          (colorType === 0 || colorType === 4)
        ) {
          return null;
        }
        sawPalette = true;
        break;
      case "IDAT":
        if (
          !dimensions || imageDataEnded ||
          (colorType === 3 && !sawPalette)
        ) {
          return null;
        }
        sawImageData = true;
        imageDataBytes += dataLength;
        for (
          let index = dataOffset;
          index < dataEnd && zlibPrefix.length < 2;
          index++
        ) {
          zlibPrefix.push(bytes[index]);
        }
        break;
      case "IEND":
        if (
          !dimensions || !sawImageData || imageDataBytes < 6 ||
          zlibPrefix.length !== 2 || (zlibPrefix[0] & 0x0f) !== 8 ||
          (zlibPrefix[0] >>> 4) > 7 ||
          ((zlibPrefix[0] << 8) | zlibPrefix[1]) % 31 !== 0 ||
          (zlibPrefix[1] & 0x20) !== 0 || dataLength !== 0 ||
          chunkEnd !== bytes.length
        ) {
          return null;
        }
        return dimensions;
      case "iCCP":
      case "zTXt":
        // These chunks contain compressed metadata with no trustworthy
        // decompressed-size bound. Rendering does not require them.
        return null;
      case "iTXt":
        // The compression flag is the byte after the null-terminated keyword.
        // Reject malformed or compressed international text without inflating it.
        {
          let separator = dataOffset;
          while (separator < dataEnd && bytes[separator] !== 0) separator++;
          if (
            separator === dataOffset || separator + 2 >= dataEnd ||
            bytes[separator + 1] !== 0 || bytes[separator + 2] !== 0
          ) {
            return null;
          }
        }
        metadataBytes += dataLength;
        break;
      default:
        // Unknown critical chunks cannot be ignored safely. Ancillary chunks are
        // never interpreted, but their aggregate size is bounded.
        if ((bytes[typeOffset] & 0x20) === 0) return null;
        metadataBytes += dataLength;
        break;
    }

    if (metadataBytes > MAX_METADATA_BYTES) return null;
    if (sawImageData && chunkType !== "IDAT") imageDataEnded = true;
    offset = chunkEnd;
  }

  return null;
}

function isJpegStartOfFrame(marker: number): boolean {
  return (marker >= 0xc0 && marker <= 0xc3) ||
    (marker >= 0xc5 && marker <= 0xc7) ||
    (marker >= 0xc9 && marker <= 0xcb) ||
    (marker >= 0xcd && marker <= 0xcf);
}

function parseJpeg(bytes: Uint8Array): Dimensions | null {
  if (
    bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8
  ) {
    return null;
  }

  let offset = 2;
  let elements = 0;
  let metadataBytes = 0;
  let dimensions: Dimensions | null = null;
  let sawScan = false;
  let inScan = false;
  let scanPayloadBytes = 0;

  while (offset < bytes.length) {
    if (inScan) {
      let foundMarker = false;
      while (offset < bytes.length) {
        if (bytes[offset] !== 0xff) {
          scanPayloadBytes++;
          offset++;
          continue;
        }
        const markerOffset = offset;
        while (offset < bytes.length && bytes[offset] === 0xff) offset++;
        if (offset >= bytes.length) return null;
        const marker = bytes[offset];
        if (marker === 0x00 || (marker >= 0xd0 && marker <= 0xd7)) {
          if (marker === 0x00) scanPayloadBytes++;
          offset++;
          continue;
        }
        if (scanPayloadBytes === 0) return null;
        offset = markerOffset;
        inScan = false;
        foundMarker = true;
        break;
      }
      if (!foundMarker) return null;
    }

    if (++elements > MAX_CONTAINER_ELEMENTS || bytes[offset] !== 0xff) {
      return null;
    }
    while (offset < bytes.length && bytes[offset] === 0xff) offset++;
    if (offset >= bytes.length) return null;
    const marker = bytes[offset++];
    if (marker === 0x00 || marker === 0xd8) return null;

    if (marker === 0xd9) {
      return dimensions && sawScan && scanPayloadBytes > 0 &&
          offset === bytes.length
        ? dimensions
        : null;
    }
    if (marker === 0x01) continue;
    if (marker >= 0xd0 && marker <= 0xd7) return null;

    if (bytes.length - offset < 2) return null;
    const segmentLength = readUint16BigEndian(bytes, offset);
    if (segmentLength < 2) return null;
    const dataOffset = offset + 2;
    const segmentEnd = offset + segmentLength;
    if (segmentEnd < dataOffset || segmentEnd > bytes.length) return null;

    if (isJpegStartOfFrame(marker)) {
      if (dimensions || segmentLength < 11) return null;
      const precision = bytes[dataOffset];
      const height = readUint16BigEndian(bytes, dataOffset + 1);
      const width = readUint16BigEndian(bytes, dataOffset + 3);
      const componentCount = bytes[dataOffset + 5];
      if (
        precision < 8 || precision > 16 || componentCount < 1 ||
        componentCount > 4 || segmentLength !== 8 + componentCount * 3 ||
        !hasSafeDimensions({ width, height })
      ) {
        return null;
      }
      dimensions = { width, height };
    } else if (marker === 0xda) {
      if (!dimensions || segmentLength < 8) return null;
      const componentCount = bytes[dataOffset];
      if (
        componentCount < 1 || componentCount > 4 ||
        segmentLength !== 6 + componentCount * 2
      ) {
        return null;
      }
      sawScan = true;
      scanPayloadBytes = 0;
      inScan = true;
    } else if ((marker >= 0xe0 && marker <= 0xef) || marker === 0xfe) {
      metadataBytes += segmentLength - 2;
      if (metadataBytes > MAX_METADATA_BYTES) return null;
    }

    offset = segmentEnd;
  }

  return null;
}

function parseVp8Dimensions(
  bytes: Uint8Array,
  offset: number,
  length: number,
): Dimensions | null {
  if (
    length <= 10 || (bytes[offset] & 1) !== 0 ||
    bytes[offset + 3] !== 0x9d || bytes[offset + 4] !== 0x01 ||
    bytes[offset + 5] !== 0x2a
  ) {
    return null;
  }
  const frameTag = readUint24LittleEndian(bytes, offset);
  const firstPartitionLength = frameTag >>> 5;
  if (
    ((frameTag >>> 1) & 0x07) > 3 || ((frameTag >>> 4) & 1) !== 1 ||
    firstPartitionLength === 0 || firstPartitionLength > length - 10
  ) {
    return null;
  }
  const width = readUint16LittleEndian(bytes, offset + 6) & 0x3fff;
  const height = readUint16LittleEndian(bytes, offset + 8) & 0x3fff;
  return hasSafeDimensions({ width, height }) ? { width, height } : null;
}

function parseVp8LosslessDimensions(
  bytes: Uint8Array,
  offset: number,
  length: number,
): Dimensions | null {
  if (length <= 5 || bytes[offset] !== 0x2f) return null;
  const bits = readUint32LittleEndian(bytes, offset + 1);
  if (((bits >>> 29) & 0x07) !== 0) return null;
  const width = 1 + (bits & 0x3fff);
  const height = 1 + ((bits >>> 14) & 0x3fff);
  return hasSafeDimensions({ width, height }) ? { width, height } : null;
}

function parseWebp(bytes: Uint8Array): Dimensions | null {
  if (
    bytes.length < 20 || fourCc(bytes, 0) !== "RIFF" ||
    fourCc(bytes, 8) !== "WEBP" ||
    readUint32LittleEndian(bytes, 4) !== bytes.length - 8
  ) {
    return null;
  }

  let offset = 12;
  let elements = 0;
  let metadataBytes = 0;
  let extendedDimensions: Dimensions | null = null;
  let imageDimensions: Dimensions | null = null;
  let extendedFlags = 0;
  let sawIcc = false;
  let sawExif = false;
  let sawXmp = false;
  let sawAlpha = false;
  let losslessAlpha = false;
  let imageType = "";

  while (offset < bytes.length) {
    if (++elements > MAX_CONTAINER_ELEMENTS || bytes.length - offset < 8) {
      return null;
    }
    const chunkType = fourCc(bytes, offset);
    const dataLength = readUint32LittleEndian(bytes, offset + 4);
    const dataOffset = offset + 8;
    const dataEnd = dataOffset + dataLength;
    const chunkEnd = dataEnd + (dataLength & 1);
    if (
      dataEnd < dataOffset || chunkEnd < dataEnd || chunkEnd > bytes.length ||
      ((dataLength & 1) === 1 && bytes[dataEnd] !== 0)
    ) {
      return null;
    }

    if (
      elements === 1 && chunkType !== "VP8X" && chunkType !== "VP8 " &&
      chunkType !== "VP8L"
    ) {
      return null;
    }

    if (elements === 1 && chunkType === "VP8X") {
      if (dataLength !== 10) return null;
      extendedFlags = bytes[dataOffset];
      if (
        (extendedFlags & 0xc3) !== 0 || bytes[dataOffset + 1] !== 0 ||
        bytes[dataOffset + 2] !== 0 || bytes[dataOffset + 3] !== 0
      ) {
        // Reserved bits and animated WebP are rejected. Animation can multiply
        // decode work far beyond the canvas pixel limit.
        return null;
      }
      const width = 1 + readUint24LittleEndian(bytes, dataOffset + 4);
      const height = 1 + readUint24LittleEndian(bytes, dataOffset + 7);
      if (!hasSafeDimensions({ width, height })) return null;
      extendedDimensions = { width, height };
    } else if (chunkType === "VP8X") {
      return null;
    } else if (chunkType === "VP8 ") {
      if (imageDimensions) return null;
      imageDimensions = parseVp8Dimensions(bytes, dataOffset, dataLength);
      if (!imageDimensions) return null;
      imageType = "VP8 ";
    } else if (chunkType === "VP8L") {
      if (imageDimensions) return null;
      imageDimensions = parseVp8LosslessDimensions(
        bytes,
        dataOffset,
        dataLength,
      );
      if (!imageDimensions) return null;
      losslessAlpha =
        ((readUint32LittleEndian(bytes, dataOffset + 1) >>> 28) & 1) === 1;
      imageType = "VP8L";
    } else if (chunkType === "ICCP") {
      if (!extendedDimensions || sawIcc) return null;
      sawIcc = true;
      metadataBytes += dataLength;
    } else if (chunkType === "EXIF") {
      if (!extendedDimensions || sawExif) return null;
      sawExif = true;
      metadataBytes += dataLength;
    } else if (chunkType === "XMP ") {
      if (!extendedDimensions || sawXmp) return null;
      sawXmp = true;
      metadataBytes += dataLength;
    } else if (chunkType === "ALPH") {
      if (
        !extendedDimensions || sawAlpha || imageDimensions || dataLength === 0
      ) {
        return null;
      }
      sawAlpha = true;
    } else {
      if (!extendedDimensions) return null;
      metadataBytes += dataLength;
    }

    if (metadataBytes > MAX_METADATA_BYTES) return null;
    offset = chunkEnd;
  }

  if (!imageDimensions) return null;
  if (!extendedDimensions) {
    return elements === 1 ? imageDimensions : null;
  }
  if (
    imageDimensions.width !== extendedDimensions.width ||
    imageDimensions.height !== extendedDimensions.height ||
    Boolean(extendedFlags & 0x20) !== sawIcc ||
    Boolean(extendedFlags & 0x08) !== sawExif ||
    Boolean(extendedFlags & 0x04) !== sawXmp ||
    Boolean(extendedFlags & 0x10) !== (sawAlpha || losslessAlpha) ||
    (sawAlpha && imageType !== "VP8 ")
  ) {
    return null;
  }
  return extendedDimensions;
}

export function validatePrivateAlbumImage(
  bytes: Uint8Array,
  mimeType: string,
): PrivateAlbumImageValidation {
  if (
    bytes.byteLength < PRIVATE_ALBUM_MIN_IMAGE_BYTES ||
    bytes.byteLength > PRIVATE_ALBUM_MAX_IMAGE_BYTES
  ) {
    return INVALID;
  }

  let dimensions: Dimensions | null;
  switch (mimeType) {
    case "image/jpeg":
      dimensions = parseJpeg(bytes);
      break;
    case "image/png":
      dimensions = parsePng(bytes);
      break;
    case "image/webp":
      dimensions = parseWebp(bytes);
      break;
    default:
      return INVALID;
  }

  return dimensions ? { valid: true, mimeType, ...dimensions } : INVALID;
}
