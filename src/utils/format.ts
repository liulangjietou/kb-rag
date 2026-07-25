const FILE_SIZE_UNITS = ['B', 'KB', 'MB', 'GB'];

/** Formats a byte count as a human readable string, e.g. 1536 -> "1.5 KB". */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '-';
  }
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < FILE_SIZE_UNITS.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  const precision = unitIndex === 0 ? 0 : 1;
  return `${value.toFixed(precision)} ${FILE_SIZE_UNITS[unitIndex]}`;
}

/** Formats a retrieval score for display, keeping 4 decimal places. */
export function formatScore(score: number): string {
  return score.toFixed(4);
}
