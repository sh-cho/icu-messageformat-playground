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
    template: `{count, plural,
  =0 {항목 없음}
  one {# 항목}
  other {# 항목}
}`,
    args: `{
  "count": 3
}`,
  },
  {
    name: "Plural (English)",
    engine: "mf1",
    locale: "en-US",
    template: `{count, plural,
  =0 {No items}
  one {# item}
  other {# items}
}`,
    args: `{
  "count": 2
}`,
  },
  {
    name: "Plural (Russian)",
    engine: "mf1",
    locale: "ru-RU",
    template: `{count, plural,
  one {# яблоко}
  few {# яблока}
  many {# яблок}
  other {# яблока}
}`,
    args: `{
  "count": 5
}`,
  },
  {
    name: "Select (gender, Korean)",
    engine: "mf1",
    locale: "ko-KR",
    template: `{gender, select,
  male {그가}
  female {그녀가}
  other {그들이}
} 사진을 올렸습니다.`,
    args: `{
  "gender": "female"
}`,
  },
  {
    name: "Select (gender, English)",
    engine: "mf1",
    locale: "en-US",
    template: `{gender, select,
  male {He}
  female {She}
  other {They}
} uploaded a photo.`,
    args: `{
  "gender": "female"
}`,
  },
  {
    name: "Currency (USD)",
    engine: "mf1",
    locale: "en-US",
    template: "{price, number, ::currency/USD}",
    args: `{
  "price": 1234.5
}`,
  },
  {
    name: "Date (tagged)",
    engine: "mf1",
    locale: "en-US",
    template: "Expires on {exp, date, long}",
    args: `{
  "exp": { "@type": "date", "value": "2025-03-27T00:00:00Z" }
}`,
  },
  {
    name: "MF2 .match (Korean, preview)",
    engine: "mf2",
    locale: "ko-KR",
    template: `.input {$count :number}
.match $count
0   {{알림이 없습니다.}}
*   {{알림이 {$count}개 있습니다.}}`,
    args: `{
  "count": 0
}`,
  },
  {
    name: "MF2 .match (English, preview)",
    engine: "mf2",
    locale: "en-US",
    template: `.input {$count :number}
.match $count
0   {{No notifications.}}
one {{You have 1 notification.}}
*   {{You have {$count} notifications.}}`,
    args: `{
  "count": 3
}`,
  },
];
