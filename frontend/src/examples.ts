import type { Engine } from "./api";

export interface Example {
  name: string;
  engine: Engine;
  locale: string;
  template: string;
  args: string;
}

export const EXAMPLES: Example[] = [
  {
    name: "Plural (Korean)",
    engine: "mf1",
    locale: "ko-KR",
    template: "{count, plural, =0 {항목 없음} one {# 항목} other {# 항목}}",
    args: '{\n  "count": 3\n}',
  },
  {
    name: "Select (gender)",
    engine: "mf1",
    locale: "ko-KR",
    template:
      "{gender, select, male {그가} female {그녀가} other {그들이}} 사진을 올렸습니다.",
    args: '{\n  "gender": "female"\n}',
  },
  {
    name: "Currency skeleton",
    engine: "mf1",
    locale: "en-US",
    template: "{price, number, ::currency/USD}",
    args: '{\n  "price": 1234.5\n}',
  },
  {
    name: "Date (tagged)",
    engine: "mf1",
    locale: "en-US",
    template: "Expires on {exp, date, long}",
    args: '{\n  "exp": { "@type": "date", "value": "2025-03-27T00:00:00Z" }\n}',
  },
  {
    name: "MF2 .match (preview)",
    engine: "mf2",
    locale: "ko-KR",
    template:
      ".input {$count :number}\n.match $count\n1   {{알림이 1개 있습니다.}}\n*   {{알림이 {$count}개 있습니다.}}",
    args: '{\n  "count": 5\n}',
  },
];
