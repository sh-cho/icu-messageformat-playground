# ICU MessageFormat Playground — Claude Code Handoff

## 1. 목표

icu4j로 ICU MessageFormat을 **실제 렌더링**하는 웹 플레이그라운드를 만든다.
사용자가 (1) template/rule, (2) 입력 데이터, (3) locale 을 넣으면 (4) 최종 렌더 결과를 즉시 본다.

차별점: 기존 JS 기반 플레이그라운드(icusyntax.com 등)는 `intl-messageformat`이라
브라우저 `Intl`의 한계로 일부 skeleton/포맷이 어긋난다. 이 프로젝트는 **icu4j가 직접 렌더**해서
JVM 백엔드(production)가 뱉을 결과와 동일한 출력을 보장한다. icu4j는 CLDR 데이터를 jar에
번들로 들고 있어 호스트 환경과 무관하게 일관적이다.

부차 목표: 로컬에서 **한 방에 띄워** 테스트 가능해야 한다 (fat jar 또는 `docker run`).

---

## 2. 확정된 결정

| 항목 | 결정 |
|---|---|
| Backend | Kotlin + Ktor |
| Formatter | icu4j (`com.ibm.icu`) — MF1 = `text.MessageFormat`, MF2 = `message2.MessageFormatter` |
| 서빙 | **단일 jar이 UI(static) + API 둘 다 서빙** (단일 아티팩트가 핵심) |
| Frontend | React + Vite + CodeMirror 6, 4-pane 레이아웃 |
| 배포 | fat jar 기본 / 얇은 Dockerfile / GraalVM native는 stretch goal |
| MF2 | 지원하되 "Technical Preview" 라벨 필수 (프로덕션 의존 금지 대상) |

---

## 3. 범위 (Scope)

**In (v1)**
- 단일 메시지 렌더링
- locale 선택 (드롭다운)
- 인자 입력 (JSON)
- MF1 / MF2 엔진 토글
- 에러 표시 (syntax error, 누락 인자, 타입 불일치)

**Out (v1, 명시적 non-goal)**
- 카탈로그/번역 저장·관리 → 이건 Tolgee의 일이지 플레이그라운드가 아님
- 다중 메시지 일괄 처리, 인증, DB, Tolgee/Lingui 연동
- 영속화 (URL share 정도는 stretch)

이 경계를 넘으면 "플레이그라운드"가 아니라 다른 제품이 된다. 넘지 말 것.

---

## 4. 아키텍처

```
playground.jar  (단일 아티팩트, JRE만 있으면 실행)
 ├─ POST /api/format   → icu4j 렌더, JSON 반환
 ├─ GET  /api/locales  → 지원 locale 목록 (선택)
 └─ GET  /*            → 빌드된 프론트 dist (resources/static 에 번들)
```

프론트 dist를 Ktor `resources`에 넣어 static serving → jar 하나로 UI+API 완결.

---

## 5. API 계약

### `POST /api/format`

Request:
```json
{
  "engine": "mf1",
  "template": "{count, plural, one {# item} other {# items}}",
  "locale": "ko-KR",
  "args": { "count": 3 }
}
```

- `engine`: `"mf1"` | `"mf2"`
- `template`: ICU MessageFormat 문자열
- `locale`: BCP-47 태그
- `args`: 인자 맵 (타입 처리는 §6 참조)

Response (성공):
```json
{ "output": "3개 항목", "error": null }
```

Response (실패):
```json
{
  "output": null,
  "error": {
    "type": "SYNTAX | MISSING_ARG | TYPE_MISMATCH | INTERNAL",
    "message": "사람이 읽을 수 있는 설명",
    "offset": 12
  }
}
```

- 에러는 **HTTP 200 + error 바디**로 반환한다 (4xx 아님). 플레이그라운드에서 에러는
  정상 흐름이고, 프론트가 깔끔하게 표시해야 하므로.
- `offset`은 가능하면 채운다 (CodeMirror에서 에러 위치 하이라이트용). MF1은 `ParseException`,
  MF2는 빌드 시 파스 에러에서 위치를 뽑을 수 있으면 채우고, 못 뽑으면 null.

### 백엔드 렌더 코어 (스케치)

```kotlin
val locale = ULocale.forLanguageTag(req.locale)
val output = when (req.engine) {
    "mf2" -> com.ibm.icu.message2.MessageFormatter.builder()
        .setPattern(req.template)
        .setLocale(locale.toLocale())
        .build()
        .formatToString(coerce(req.args))      // Map<String, Any?>
    else  -> com.ibm.icu.text.MessageFormat(req.template, locale.toLocale())
        .format(coerce(req.args))
}
```

---

## 6. 핵심 난점: 인자 coerce (이 프로젝트의 유일한 실질 설계 포인트)

icu4j MF1은 `Map<String, Object>`를 받는데 **JSON에는 날짜 타입이 없다.**
`{exp, date, short}`는 `java.util.Date`(또는 `Calendar`)를 요구하지만 JSON에선 문자열로 들어온다.
따라서 JSON 값 → Java 타입 변환 규칙이 필요하다.

| 입력 형태 | → Java 타입 | 쓰이는 ICU 포맷 |
|---|---|---|
| number | `Number` (Long/Double) | `plural`, `selectordinal`, `number`, `#` |
| string | `String` | `select`, 일반 치환 |
| boolean | `String` ("true"/"false") | `select` |
| 태깅된 날짜 (아래) | `java.util.Date` | `date`, `time` |

**날짜는 명시적 태깅을 권장한다** (v1):
```json
{ "exp": { "@type": "date", "value": "2025-03-27T00:00:00Z" } }
```
"날짜처럼 보이는 일반 문자열"을 자동 감지하면 오인 렌더가 생기므로, v1은 태깅으로 안전하게 간다.
ISO 자동 감지는 편의 기능으로 나중에 옵션 토글로 추가 가능.

프론트에서 **인자별 타입 셀렉터**(string/number/date/bool)를 주면 UX가 더 좋지만,
v1은 "JSON 자유 입력 + 날짜 태깅 컨벤션"으로 시작해도 충분하다. 둘 다 같은 백엔드 계약을 쓴다.

`coerce()` 구현 시 주의: 숫자는 정수/실수 구분 유지(`plural` 분기에 영향), null/누락 인자는
`MISSING_ARG` 에러로 분류.

---

## 7. 기술 스택 / 버전

> 정확한 버전은 빌드 시점 최신 안정 버전으로 핀. 아래는 권장 기준선.

- JDK: 21 (LTS)
- Kotlin: 2.x
- Ktor: 3.x (`ktor-server-core`, `-netty`, `-content-negotiation`, `-serialization-kotlinx-json`)
- icu4j: 최신 안정 (`com.ibm.icu:icu4j`) — MF2는 ICU 75+ 필요, 최신(78+)이면 MF2 신 syntax 반영됨
- 빌드: Gradle + Shadow plugin (`com.gradleup.shadow`) 또는 Ktor `buildFatJar` task
- Frontend: Vite + React 18/19 + CodeMirror 6 (`@codemirror/*`)

---

## 8. 프로젝트 구조 (제안)

```
icu-playground/
├─ build.gradle.kts            # Ktor + icu4j + shadow, 프론트 dist 복사 task
├─ settings.gradle.kts
├─ Dockerfile
├─ src/main/kotlin/
│  ├─ Application.kt           # Ktor 부트, 라우팅, static serving
│  ├─ FormatRoute.kt          # POST /api/format
│  ├─ Renderer.kt             # icu4j MF1/MF2 렌더
│  ├─ Coerce.kt               # §6 인자 변환
│  └─ Models.kt               # request/response DTO (@Serializable)
├─ src/main/resources/
│  └─ static/                 # ← 프론트 빌드 결과가 복사됨 (gitignore)
└─ frontend/
   ├─ package.json
   ├─ vite.config.ts          # build.outDir = ../src/main/resources/static, dev proxy → :8080
   └─ src/
      ├─ App.tsx              # 4-pane 레이아웃
      ├─ panes/{Template,Args,Locale,Output}.tsx
      └─ api.ts               # POST /api/format
```

빌드 파이프라인: `frontend` 빌드 → dist를 `resources/static`으로 복사 → Gradle이 fat jar에 포함.
Gradle task 의존성으로 묶어 `./gradlew buildFatJar` 한 번에 끝나게 한다.

---

## 9. 구현 마일스톤

- **M1** — Ktor 스켈레톤 + `POST /api/format` MF1 only (coerce 없이 string/number만)
- **M2** — `Coerce.kt` 완성 (날짜 태깅 포함) + 에러 분류
- **M3** — MF2 엔진 토글 추가
- **M4** — 프론트 4-pane (CodeMirror template pane, JSON args pane, locale 드롭다운, output pane), 디바운스 자동 렌더
- **M5** — 프론트 dist를 jar에 번들 (static serving 확인)
- **M6** — Dockerfile + fat jar 실행 검증
- **M7** — 가드(타임아웃, 입력 크기 캡) + 에러 위치 하이라이트

M1~M3가 핵심(백엔드 가치), M4~M5가 사용성, M6~M7이 배포/위생.

---

## 10. 로컬 실행

**개발 모드**
- 백엔드: `./gradlew run` (`:8080`)
- 프론트: `cd frontend && npm run dev` (`:5173`, `/api`는 vite proxy로 `:8080` 전달)

**프로덕션 / 한 방 실행**
- `./gradlew buildFatJar` → `java -jar build/libs/playground-all.jar` → `http://localhost:8080`
- JRE만 있으면 됨. 프론트 따로 안 띄워도 됨.

**Docker**
```dockerfile
FROM eclipse-temurin:21-jre
COPY build/libs/playground-all.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```
`docker run -p 8080:8080 icu-playground`. 단일 서비스라 compose는 불필요(원하면 1-service로 감싸도 무방).

**GraalVM native (stretch, v1 비포함)**
진짜 싱글 바이너리지만 icu4j가 함정: CLDR `.res` 리소스 + 리플렉션 때문에 reachability
metadata가 필요하다. tracing agent로 config 추출하는 단계가 추가됨. JRE 없는 바이너리가
꼭 필요할 때만 착수. **v1은 fat jar로 간다.**

---

## 11. 가드 (공개 호스팅 시)

로컬 전용이면 생략 가능. 공개 엔드포인트면 기본 위생으로:
- `/api/format`에 렌더 **타임아웃** (e.g. 1~2s) — 비정상 깊은 중첩 방어
- **입력 크기 캡** (template/args 바이트 제한)
- MF는 코드 실행이 아니라 포맷터라 인젝션 위험은 낮음. 위 두 개면 충분.

---

## 12. 미해결 결정 (구현 중 확정)

1. **프론트 형태**: React + Vite (기본 채택) vs 단일 HTML 파일. 4-pane + CodeMirror 유지보수성
   때문에 React 권장. 단순함을 더 원하면 단일 HTML도 가능 — 어느 쪽이든 §5 API 계약은 동일.
2. **날짜 처리**: 태깅 전용(v1) vs ISO 자동감지 옵션 추가 시점.
3. **locale 목록 범위**: 전체 ICU locale 노출 vs 주요 N개 추림 + 자유 입력.
4. **URL share**: 상태를 쿼리스트링/해시로 인코딩해 공유 (stretch).

---

## 13. 테스트용 예시

**MF1**
```
{count, plural, =0 {항목 없음} one {# 항목} other {# 항목}}
args: { "count": 0 }   / locale: ko-KR
```
```
{gender, select, male {그가} female {그녀가} other {그들이}} 사진을 올렸습니다.
args: { "gender": "female" }
```
```
{price, number, ::currency/USD}
args: { "price": 1234.5 }   / locale: en-US → "$1,234.50"
```

**MF2** (syntax는 preview라 icu4j 버전에 따라 다를 수 있음 — 빌드된 버전 기준으로 검증)
```
.match {$count :number}
1 {{알림이 1개 있습니다.}}
* {{알림이 {$count}개 있습니다.}}
```

각 예시를 통합 테스트로 박아두면 icu4j 버전 업 시 회귀 감지에 유용.

---

## 14. 참고

- icu4j MF1: `com.ibm.icu.text.MessageFormat`
- icu4j MF2 (tech preview): `com.ibm.icu.message2.MessageFormatter` (ICU 75+)
- ICU User Guide — Formatting Messages / MessageFormat 2.0
- 주의: MF2 API는 `@Deprecated`로 표시돼 있는데 이는 "tech preview, 프로덕션 의존 금지"
  의미지 폐기 예정이 아님. 플레이그라운드 UI에 그대로 "Preview"로 노출하면 됨.

