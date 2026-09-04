// Author: owlzhangfq@gmail.com

/** 与后端 Character 分类保持一致：中文属于字母，不可被误当作特殊符号。 */
export function isStrongPassword(value: string): boolean {
  return Array.from(value).length >= 12
    && new TextEncoder().encode(value).length <= 72
    && !/\s/u.test(value)
    && /\p{Lu}/u.test(value)
    && /\p{Ll}/u.test(value)
    && /\p{Nd}/u.test(value)
    && /[^\p{L}\p{Nd}\s]/u.test(value);
}
