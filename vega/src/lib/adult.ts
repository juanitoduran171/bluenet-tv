const ACCENTS: Record<string, string> = {á: 'a', é: 'e', í: 'i', ó: 'o', ú: 'u', ü: 'u', ñ: 'n', à: 'a', è: 'e', ì: 'i', ò: 'o', ù: 'u'};

/** Categoria para adultos segun su nombre (ADULTOS, XXX, +18...). */
export function isAdult(name: string | null | undefined): boolean {
  if (!name) {
    return false;
  }
  const n = name.toLowerCase().replace(/[áéíóúüñàèìòù]/g, ch => ACCENTS[ch] || ch);
  return (
    n.includes('adult') || n.includes('xxx') || n.includes('+18') || n.includes('18+') || n.includes('porn') || n.includes('erotic')
  );
}
