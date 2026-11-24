const money = new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const quantity = new Intl.NumberFormat("en-US", { maximumFractionDigits: 6 });
const dateTime = new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeStyle: "short" });

export function formatMoney(amount: number, currency = "USD"): string {
  return `${currency} ${money.format(amount)}`;
}

export function formatQuantity(value: number): string {
  return quantity.format(value);
}

export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso));
}

/** Upper-snake enum value to sentence case, e.g. PENDING_APPROVAL -> "Pending approval". */
export function humanize(value: string): string {
  const text = value.toLowerCase().replaceAll("_", " ");
  return text.charAt(0).toUpperCase() + text.slice(1);
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}
