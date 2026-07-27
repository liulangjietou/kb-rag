// Author: owlzhangfq@gmail.com

/** Escapes one CSV field per RFC 4180 (wraps in quotes when it contains a comma/quote/newline). */
function escapeCsvField(field: string): string {
  if (/["\n,]/.test(field)) {
    return `"${field.replace(/"/g, '""')}"`;
  }
  return field;
}

/** Serializes rows of string cells into one CSV string body (CRLF line endings). */
export function toCsv(rows: string[][]): string {
  return rows.map((row) => row.map(escapeCsvField).join(',')).join('\r\n');
}

/**
 * Triggers a browser download of the given rows as a .csv file (eval report export, see
 * M4b-CONTRACTS.md section 5 "导出 CSV"). Prefixes a UTF-8 BOM so Excel renders Chinese headers
 * correctly instead of mis-detecting the encoding.
 */
export function downloadCsv(filename: string, rows: string[][]): void {
  const csv = `\uFEFF${toCsv(rows)}`;
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
}
