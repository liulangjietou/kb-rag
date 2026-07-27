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

/** Formats a 0..1 ratio (e.g. M9's annotation-migration Dice score) as a rounded percentage, e.g. 0.357 -> "36%". */
export function formatPercent(ratio: number): string {
  return `${Math.round(ratio * 100)}%`;
}

/** Formats an epoch-millis timestamp (e.g. chat msg_time) as a locale date-time string. */
export function formatEpochMillis(epochMillis: number | null | undefined): string {
  if (epochMillis === null || epochMillis === undefined || !Number.isFinite(epochMillis)) {
    return '-';
  }
  return new Date(epochMillis).toLocaleString('zh-CN', { hour12: false });
}
