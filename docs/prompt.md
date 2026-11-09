# 02. Prompt: 프롬프트 템플릿과 메시지 다루기

> 📦 패키지: `com.brunosong.springai.prompt`
> 🧰 버전: Spring Boot 4.1.2 / Spring AI 2.0.2
> 🤖 모델: Gemini(`/explain`, `/role`), Ollama(`/translate`, `/message`)
> 🏛️ 아키텍처: 헥사고날 **미적용**
> 📚 선행: [01. Basic](basic.md)

---

## 이 장에서 배우는 것

1. 프롬프트에 **변수**를 넣는 방법 (`{변수}` + `.param()`)
2. system 메시지에도 변수 넣기
3. 프롬프트를 **파일로 분리**하기 (`.st` 파일 + `Resource`)
4. AI에게 가는 요청이 사실은 **메시지 목록**이라는 것
5. `.messages()`로 예시를 보여 주는 **Few-shot 프롬프팅**

---

## 📖 용어 정리

| 용어 | 설명 |
|---|---|
| **템플릿** (Template) | 빈칸이 뚫린 틀 문장. 빈칸만 바꿔 가며 재사용한다. |
| **템플릿 변수** | 템플릿 안의 빈칸. Spring AI에서는 `{이름}` 형식이다. |
| **렌더링** (Rendering) | 템플릿의 변수 자리에 실제 값을 채워 **완성된 문장을 만드는 것** |
| **PromptTemplate** | 템플릿과 변수를 받아 렌더링하고 `Prompt` 객체를 만드는 Spring AI 클래스 |
| **Prompt** (클래스) | 메시지 목록 + 옵션을 묶은 "완성된 요청" 객체 |
| **StringTemplate** (`.st`) | Spring AI가 기본으로 쓰는 템플릿 엔진. 그래서 템플릿 파일 확장자를 관례상 `.st`로 쓴다. 내용은 그냥 텍스트다. |
| **구분자** (Delimiter) | 템플릿에서 변수의 시작과 끝을 표시하는 문자. 기본값은 `{`와 `}`다. |
| **람다** (Lambda) | `u -> u.text(...)`처럼 메서드에 넘기는 짧은 함수. "이 객체를 받아서 이렇게 설정해 줘"라는 뜻이다. |
| **Resource** | 스프링이 파일, 클래스패스, URL 같은 자원을 같은 방식으로 읽도록 만든 인터페이스 (`org.springframework.core.io.Resource`) |
| **클래스패스** (classpath) | 자바가 클래스와 자원 파일을 찾는 경로. 스프링 부트에서는 `src/main/resources`가 빌드 후 클래스패스의 루트가 된다. |
| **`@Value`** | 설정 값이나 자원을 필드에 주입하는 스프링 어노테이션 (`@Value("classpath:...")`) |
| **무상태** (Stateless) | 이전 요청을 기억하지 않는 성질. LLM API는 무상태라서, 대화를 이어 가려면 매번 이전 대화를 다시 보내야 한다. |
| **대화 이력** (Conversation History) | 지금까지 주고받은 메시지 목록. AI가 "기억하는" 것처럼 보이게 만드는 재료다. |
| **Shot** | 프롬프트에 넣는 **예시** 하나 |
| **Zero-shot** | 예시 없이 지시만으로 시키는 방식 |
| **Few-shot** | 예시 몇 개를 보여 주고 따라 하게 시키는 방식 |
| **SystemMessage / UserMessage / AssistantMessage** | 각 역할의 메시지를 표현하는 Spring AI 클래스 |

---

## 1. 왜 템플릿이 필요한가?

basic 장에서는 질문을 문자열 그대로 보냈다. 실무에서는 대부분 **틀은 고정하고 일부만 바꿔서** 보낸다.

```
"다음 문장을 {language}로 번역해줘. 문장: {text}"    ← 틀 (템플릿)
         language = 영어, text = 오늘 날씨 좋다     ← 변수
```

문자열을 `+`로 이어 붙여도 되지만 템플릿을 쓰면 이런 장점이 있다.
- 프롬프트의 **모양이 한눈에** 보인다.
- 프롬프트를 **파일로 분리**해서 코드 수정 없이 고칠 수 있다.
- 같은 템플릿을 여러 곳에서 **재사용**할 수 있다.

---

## 2. 핵심 개념

| 이름 | 설명 |
|---|---|
| **템플릿 변수 `{이름}`** | 프롬프트 안의 빈칸. `.param("이름", 값)`으로 채운다. |
| **PromptTemplate** | 템플릿 + 변수 → 완성된 프롬프트를 만드는 클래스. ChatClient도 내부에서 이걸 쓴다. |
| **Prompt** | 메시지 목록 + 옵션을 묶은 "완성된 요청" 객체 |
| **Message 클래스** | `SystemMessage`, `UserMessage`, `AssistantMessage`. 역할(role)을 객체로 표현한 것 |
| **`.st` 파일** | StringTemplate 형식의 템플릿 파일. Spring AI의 기본 템플릿 엔진이 StringTemplate이라 관례상 이 확장자를 쓴다. (내용은 그냥 텍스트) |

### 💡 람다 방식: `.user(u -> ...)`

```java
.user("문자열")                                     // 고정 문자열
.user(u -> u.text("{topic} 설명해줘")               // 템플릿 + 변수
            .param("topic", topic))
```

문자열 대신 **람다**를 넘기면 `text`(템플릿)와 `param`(변수)을 함께 지정할 수 있다.
`.system(s -> ...)`도 똑같다.

---

## 3. 실습 1: user 메시지에 변수 넣기

```java
@GetMapping("/explain")
public String explain(@RequestParam String topic,
                      @RequestParam(defaultValue = "3") int line) {
    return gemini.prompt()
            .user(u -> u.text("{topic}에 대해 {lines}줄로 설명해줘")
                    .param("topic", topic)
                    .param("lines", line))
            .call()
            .content();
}
```

```
GET /prompt/explain?topic=블랙홀&line=2
→ AI가 받는 user 메시지: "블랙홀에 대해 2줄로 설명해줘"
```

### 포인트
- 템플릿의 `{lines}`와 `.param("lines", ...)`의 **이름이 같아야** 값이 채워진다.
- 자바 변수 이름(`line`)과 템플릿 변수 이름(`lines`)은 달라도 된다. 연결 고리는 `.param()`의 첫 번째 인자다.
- 값은 `String`이 아니어도 된다. (`int line`을 그대로 넘김)

---

## 4. 실습 2: system 메시지에 변수 넣기

```java
@GetMapping("/role")
public String role(@RequestParam String role, @RequestParam String q) {
    return gemini.prompt()
            .system(s -> s.text("너는 {role}이야. 그 말투로 답해").param("role", role))
            .user(q)
            .call()
            .content();
}
```

```
GET /prompt/role?role=해적&q=오늘 점심 뭐 먹을까?
```

### 🐛 실습 중 실제로 겪은 실수

```java
s.text("너는 {}이야. 그 말투로 답해").param("role", role)   // ❌ 변수 이름이 없음
s.text("너는 {role}이야. 그 말투로 답해").param("role", role) // ✅
```

`{}`처럼 이름 없는 중괄호는 변수가 아니다. `role` 값이 들어갈 자리가 없다.

### 🔗 basic 실습 5와 연결
이 엔드포인트는 요청에서 `.system()`을 쓰므로, `AiClientConfig`의 `defaultSystem("항상 한국말로 답해")`가 **교체**되어 적용되지 않는다.

---

## 5. 실습 3: 템플릿을 파일로 분리하기

### 5-1. 템플릿 파일 만들기

`src/main/resources/prompts/translate.st`
```
다음 문장을 {language}로 번역해줘. 번역문만 출력해.
문장: {text}
```

### 5-2. 파일 주입받아 사용하기

```java
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;   // ⚠️ jakarta.annotation.Resource 아님!

@Value("classpath:prompts/translate.st")
private Resource translatePrompt;

@GetMapping("/translate")
public String translate(@RequestParam String language, @RequestParam String text) {
    return ollama.prompt()
            .user(u -> u.text(translatePrompt)      // 문자열 대신 Resource를 넘김
                    .param("language", language)
                    .param("text", text))
            .call()
            .content();
}
```

```
GET /prompt/translate?language=영어&text=오늘 날씨 좋다
```

### 포인트
| 항목 | 설명 |
|---|---|
| `classpath:` | `src/main/resources` 아래를 가리킨다. |
| `Resource` | 스프링이 파일, 클래스패스, URL 등을 **같은 방식으로** 읽도록 추상화한 인터페이스 (`org.springframework.core.io.Resource`) |
| `u.text(Resource)` | `text()`는 `String`뿐 아니라 `Resource`도 받는다. `.system(Resource)`도 가능 |

### ⚠️ import 주의
IDE 자동 import에 두 개가 뜬다.
- ✅ `org.springframework.core.io.Resource`: 파일 같은 자원 (이번에 쓸 것)
- ❌ `jakarta.annotation.Resource`: 의존성 주입용 **어노테이션** (전혀 다른 것)

### 🔍 관찰 포인트: system 규칙과 user 요청이 충돌하면?
`/translate`는 `ollama`를 쓰고 요청에서 `.system()`을 쓰지 않는다.
그래서 `defaultSystem("항상 한국말로 답해")`가 **그대로 적용된다.**

```
[system] 항상 한국말로 답해
[user]   다음 문장을 영어로 번역해줘. 번역문만 출력해. 문장: 오늘 날씨 좋다
```

"한국말로 답해"와 "영어로 번역해줘"가 충돌한다. 모델이 어느 쪽을 따르는지 확인해 보자.
→ 기본 system 메시지는 **모든 요청에 영향을 준다**는 걸 기억해야 한다.

---

## 6. 실습 5: `.messages()`와 Few-shot 프롬프팅

### 6-1. 먼저 알아야 할 사실: AI는 아무것도 기억하지 못한다

ChatGPT가 앞의 대화를 기억하는 것처럼 보이는 건, **매번 지금까지의 대화 전체를 다시 보내기 때문**이다.
AI 입장에서 요청 하나하나는 처음 보는 대화다. 그래서 AI에게 보내는 요청은 항상 **메시지 목록**이다.

```
[system]    너는 친절한 선생님이야.
[user]      내 이름은 철수야.
[assistant] 안녕 철수야!
[user]      내 이름이 뭐라고?      ← 이번 질문
```

### 6-2. ChatClient가 목록을 만드는 순서

Spring AI 2.0.2 소스(`DefaultChatClientUtils`)를 보면 이 순서로 목록을 만든다.

```java
chatClient.prompt()
    .system("...")     // ① [system]
    .messages(...)     // ② 넣은 메시지들이 순서대로
    .user("...")       // ③ [user] (항상 마지막)
```

| 메서드 | 하는 일 |
|---|---|
| `.system()` | system 메시지 **1개** |
| `.user()` | user 메시지 **1개** |
| `.messages()` | 메시지 **여러 개**를 직접 만들어 중간에 끼워 넣기. 종류도 직접 고른다. |

### 6-3. 코드: 가짜 대화 이력 만들기

```java
@GetMapping("/message")
public String message(@RequestParam String text) {
    return ollama.prompt()
            .messages(
                    new UserMessage("사과"),   new AssistantMessage("🍎 사과 - 빨갛고 아삭한 과일"),
                    new UserMessage("바나나"), new AssistantMessage("🍌 바나나 - 노랗고 길쭉한 과일")
            )
            .user(text)
            .call()
            .content();
}
```

`GET /prompt/message?text=포도`를 호출하면 AI가 실제로 받는 목록은 이렇다.

```
[system]    항상 한국말로 답해                   ← defaultSystem
[user]      사과
[assistant] 🍎 사과 - 빨갛고 아삭한 과일         ← 가짜 (AI가 한 적 없는 답)
[user]      바나나
[assistant] 🍌 바나나 - 노랗고 길쭉한 과일       ← 가짜
[user]      포도                                 ← 진짜 질문
```

AI는 "나는 이 형식으로 답해 왔구나"라고 판단하고 같은 형식으로 답한다.
→ `🍇 포도 - 보라색이고 알이 작은 과일`

### 6-4. Few-shot이란?

- **shot = 예시**. 예시 몇 개(few)를 보여 주고 따라 하게 하는 기법이다.
- 예시 없이 지시만 하는 것은 **Zero-shot**이라고 한다.
- 형식을 **말로 설명**하는 것보다 **예시를 보여 주는** 것이 훨씬 잘 지켜진다.
  (사람에게 "이모지, 이름, 하이픈, 설명 순으로 써"라고 하는 것보다 예시 두 개를 보여 주는 게 빠른 것과 같다.)

| 방식 | 프롬프트 | 형식 일관성 |
|---|---|---|
| Zero-shot | system에 "이모지 - 설명 형식으로 답해" | 모델에 따라 들쭉날쭉 |
| Few-shot | `.messages()`로 예시 2개 | 거의 그대로 따라 함 |

🧪 **실험해 보기**: `.messages(...)`를 넣은 버전과 뺀 버전에 `포도`를 보내서 결과를 비교해 보자.

### 6-5. 다음 장과의 연결
**memory** 장의 "이전 대화 기억하기"도 원리가 같다.
저장해 둔 **진짜 대화 이력**을 이 메시지 목록에 넣어 주는 것이다.
지금은 직접 넣고, 나중에는 Spring AI(ChatMemory)가 자동으로 넣어 준다는 차이뿐이다.

---

## 7. 자주 하는 실수 ⚠️

| 증상 | 원인 / 해결 |
|---|---|
| 변수 자리에 값이 안 들어감 | 템플릿의 `{이름}`과 `.param("이름", …)`의 이름이 다르다. |
| `{}`를 썼는데 동작 안 함 | 이름 없는 중괄호는 변수가 아니다. → `{role}`처럼 이름을 붙인다. |
| 템플릿 렌더링 에러 | 변수를 템플릿에 썼는데 `.param()`으로 값을 안 넣었다. |
| 프롬프트에 JSON 예시를 넣었더니 에러 | `{ }`를 변수로 인식한다. → 중괄호가 필요하면 다른 구분자를 쓰는 렌더러를 설정해야 한다. (structured 장에서 다룸) |
| `Resource`를 import 했는데 컴파일 에러 | `jakarta.annotation.Resource`를 잘못 골랐다. → `org.springframework.core.io.Resource` |
| `/translate` 호출 시 파일 못 찾음 | `src/main/resources/prompts/translate.st` 경로와 파일명을 확인한다. |
| 요청한 언어와 다른 언어로 답함 | `defaultSystem`의 "항상 한국말로 답해"와 충돌하고 있다. |

---

## 8. 확인 문제 ✍️

1. `.user("문자열")`과 `.user(u -> u.text(...).param(...))`의 차이는?
2. 템플릿에 `{name}`을 썼다면 `.param()`의 첫 번째 인자는?
3. `@Value("classpath:prompts/a.st")`에서 `classpath:`는 어느 폴더를 가리키나?
4. AI가 앞의 대화를 "기억하는" 것처럼 보이는 원리는?
5. `.system()`, `.messages()`, `.user()`를 모두 쓰면 메시지 목록의 순서는?
6. Few-shot 프롬프팅에서 `AssistantMessage`를 넣는 이유는?

<details>
<summary>정답 보기</summary>

1. 앞은 고정 문자열이고, 뒤는 템플릿과 변수를 함께 넘겨서 변수를 채운 문장을 만든다.
2. `"name"`
3. `src/main/resources` (빌드 후 클래스패스 루트)
4. 매번 지금까지의 대화 전체를 메시지 목록으로 다시 보내기 때문이다. AI 자체는 아무것도 기억하지 않는다.
5. system → messages(넣은 순서대로) → user
6. "AI가 전에 이런 형식으로 답했다"는 가짜 이력을 만들어서, 같은 형식으로 답하도록 유도하기 위해서다.

</details>

---

## 9. 남은 실습

- [ ] **실습 4**: `PromptTemplate`을 직접 써 보기. `render()`로 완성된 문장을 로그로 찍고, `create()`로 만든 `Prompt`를 `chatClient.prompt(prompt)`로 호출하기
  ```java
  PromptTemplate template = PromptTemplate.builder()
          .template("{topic}에 대해 한 줄로 설명해줘.")
          .build();
  String rendered = template.render(Map.of("topic", topic));
  Prompt prompt = template.create(Map.of("topic", topic));
  ```
- [ ] **실습 6**: basic 실습 5의 "system 교체" 문제를 템플릿 변수로 해결하기
  - Config: `.defaultSystem("항상 한국말로 답해. 너는 {role}이야.")`
  - 요청: `.system(s -> s.param("role", "선생님"))` (`text` 없이 `param`만 주면 기본 문장은 유지되고 변수만 채워진다)

## 10. 다음 단계

- AI의 답을 `String`이 아니라 **Java 객체(record)로** 받으려면 → **structured** 장 (`.entity()`)
