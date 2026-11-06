# 01. Basic: Spring AI로 처음 AI에게 말 걸기

> 📦 패키지: `com.brunosong.springai.basic`
> 🧰 버전: Spring Boot 4.1.2 / Spring AI 2.0.2
> 🤖 모델: Gemini(`gemini-2.5-flash`), Ollama(`gemma3`)
> 🏛️ 아키텍처: 헥사고날 **미적용** (구조보다 Spring AI 자체에 집중하기 위해 컨트롤러에서 바로 호출)

---

## 이 장에서 배우는 것

1. Spring AI가 무엇이고 왜 쓰는지
2. `ChatModel`과 `ChatClient`의 차이
3. AI에게 질문하고 답을 받는 기본 코드
4. `system` 메시지로 AI의 역할 정해 주기
5. `temperature` 옵션으로 답변의 성격 바꾸기
6. 응답 안에 들어 있는 정보(토큰 사용량) 꺼내 보기
7. 공통 설정을 `defaultXxx`로 ChatClient에 미리 넣기

---

## 1. Spring AI란?

AI 모델(Gemini, GPT, Claude, Ollama 등)은 회사마다 **API 형식이 전부 다르다.**
요청 JSON 모양, 인증 방식, 응답 구조가 제각각이라 모델을 바꾸면 코드도 다 바꿔야 한다.

Spring AI는 이 차이를 **하나의 공통 인터페이스로 감싸 주는 추상화 계층**이다.
JDBC가 DB 종류와 상관없이 같은 코드로 DB를 다루게 해 주는 것과 같은 개념이다.

```
         내 코드 (ChatClient 사용)
                 │
          ┌──────┴──────┐   ← Spring AI가 차이를 흡수
          ▼             ▼
   GoogleGenAiChatModel   OllamaChatModel
          │             │
          ▼             ▼
     Gemini API     로컬 Ollama 서버
```

👉 이 장의 예제에서도 Gemini와 Ollama를 **완전히 같은 코드 모양**으로 호출한다.

---

## 2. 핵심 개념

### 2-1. ChatModel: AI와 실제로 통신하는 객체

- 모델별 구현체가 있다: `GoogleGenAiChatModel`, `OllamaChatModel`, ...
- **스타터 의존성을 추가하면 Spring Boot가 자동으로 빈을 만들어 준다.**
  (API 키, 모델 이름은 `application.yaml`에서 읽음)
- 직접 써도 되지만 API가 저수준이라 불편하다.

### 2-2. ChatClient: ChatModel을 편하게 쓰는 도구 ⭐

- `ChatModel`을 감싸서 **메서드 체이닝(Fluent API)** 으로 쓸 수 있게 해 준다.
- `RestClient`와 `WebClient`가 HTTP 호출을 편하게 해 주는 것처럼, `ChatClient`는 AI 호출을 편하게 해 준다.
- **앞으로의 모든 실습은 거의 `ChatClient`로 진행한다.**

### 2-3. Message: AI에게 보내는 말의 종류

AI와의 대화는 "누가 한 말인지"(역할, role)가 붙은 메시지 목록이다.

| 역할 | 의미 | 예시 |
|---|---|---|
| `system` | AI의 **역할, 규칙, 말투**를 정하는 지시. 사용자에게는 보이지 않는 "무대 뒤 지시서" | "너는 친절한 선생님이야. 3줄 이내로 답해." |
| `user` | 사용자의 질문 | "하늘은 왜 파래?" |
| `assistant` | AI의 답변 | "햇빛이 공기에 부딪혀서..." |

### 2-4. ChatOptions: 모델의 동작 설정

| 옵션 | 의미 |
|---|---|
| `temperature` | 답변의 **무작위성**. 0에 가까울수록 일관되고, 높을수록 다양하고 창의적 |
| `maxTokens` | 답변의 최대 길이 (토큰 단위) |
| `model` | 사용할 모델 이름 (yaml 설정을 요청 단위로 덮어쓸 때) |

### 2-5. ChatResponse: 응답 전체

`.content()`는 답변 **텍스트만** 준다.
`.chatResponse()`는 아래 구조 **전체**를 준다.

```
ChatResponse
 ├─ getResult()                      → Generation (답변 1개)
 │    └─ getOutput()                 → AssistantMessage
 │         └─ getText()              → "답변 텍스트"
 └─ getMetadata()                    → ChatResponseMetadata
      └─ getUsage()                  → Usage (입력/출력/전체 토큰 수)
```

### 💡 토큰(Token)이란?

AI가 글을 처리하는 단위로, 대략 단어 조각이라고 보면 된다.
**유료 API는 토큰 수로 과금하고, 무료 API는 토큰 수로 사용량을 제한한다.**
그래서 토큰 사용량을 확인하는 습관이 중요하다.

---

## 3. 프로젝트 설정

### 3-1. 의존성 (`build.gradle`)

```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.ai:spring-ai-starter-model-google-genai'
    implementation 'org.springframework.ai:spring-ai-starter-model-ollama'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.ai:spring-ai-bom:2.0.2"
    }
}
```

- **BOM**(Bill of Materials): Spring AI 모듈들의 버전을 한 번에 맞춰 준다. 그래서 개별 의존성에는 버전을 쓰지 않는다.
- Spring Boot 4.x에는 Spring AI **2.x**를 써야 한다. (Boot 3.x에는 Spring AI 1.x)
- Gemini 무료 API(Google AI Studio 키) → `google-genai` 스타터
  - `vertex-ai-gemini` 스타터는 GCP 프로젝트와 결제 설정이 필요하므로 주의

### 3-2. 설정 (`application.yaml`)

```yaml
spring:
  ai:
    google:
      genai:
        api-key: ${GEMINI_API_KEY}
        chat:
          model: gemini-2.5-flash
    ollama:
      base-url: http://localhost:11434
      chat:
        model: gemma3
    chat:
      client:
        enabled: false     # 이유는 3-3에서 설명
```

- API 키는 **절대 코드나 커밋되는 파일에 쓰지 않는다.**
  → 환경변수를 쓰거나, `application-local.yaml`(`.gitignore`에 등록됨)에 둔다.
- ⚠️ Spring AI 2.0부터 `chat.options.model`은 deprecated되었다. `chat.model`을 쓴다.
  (인터넷의 1.x 예제를 따라 할 때 자주 헷갈리는 부분)

### 3-3. 모델이 2개일 때의 문제와 해결 (`AiClientConfig`)

#### 문제
보통 Spring AI는 `ChatClient.Builder`를 자동으로 만들어 준다.
그런데 스타터를 2개 넣으면 `ChatModel` 빈도 2개가 되어, **어느 모델로 Builder를 만들지 정할 수 없다.**

#### 해결
1. `spring.ai.chat.client.enabled: false`로 자동 생성을 끈다.
2. `ChatClient`를 모델별로 **직접** 만들고 이름을 붙인다.

```java
@Configuration
public class AiClientConfig {

    @Bean
    public ChatClient geminiChatClient(GoogleGenAiChatModel googleGenAiChatModel) {
        return ChatClient.builder(googleGenAiChatModel).build();
    }

    @Bean
    public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
        return ChatClient.builder(ollamaChatModel).build();
    }
}
```

| 포인트 | 이유 |
|---|---|
| 파라미터를 `ChatModel`이 아닌 **구체 클래스**로 받음 | `ChatModel` 타입 빈이 2개라 인터페이스 타입으로 받으면 어느 쪽인지 모호해진다. |
| 빈 이름 = 메서드 이름 | `geminiChatClient`, `ollamaChatClient`라는 이름으로 등록된다. |
| `ChatClient.builder(model)` | `.create(model)`과 결과는 같지만, 나중에 `.defaultSystem()` 같은 공통 설정을 붙일 수 있다. |

---

## 4. 실습 1: AI에게 질문하고 답 받기

### 4-1. 코드

```java
@RestController
@RequestMapping("/basic")
public class BasicController {

    private final ChatClient gemini;
    private final ChatClient ollama;

    // 같은 타입(ChatClient) 빈이 2개 → @Qualifier로 이름을 지정해서 주입
    public BasicController(@Qualifier("geminiChatClient") ChatClient gemini,
                           @Qualifier("ollamaChatClient") ChatClient ollama) {
        this.gemini = gemini;
        this.ollama = ollama;
    }

    @GetMapping("/ollama")
    public String ollama(@RequestParam String q) {
        return ollama.prompt()   // ① 요청 만들기 시작
                .user(q)         // ② 사용자 질문 넣기
                .call()          // ③ AI 호출 (답이 다 올 때까지 기다림)
                .content();      // ④ 답변 텍스트만 꺼내기
    }
}
```

### 4-2. 코드 흐름 이해하기

```
GET /basic/ollama?q=안녕
        │
        ▼
 ollama.prompt().user("안녕")     → [user: "안녕"] 메시지 생성
        │
        ▼ .call()
 OllamaChatModel → HTTP POST http://localhost:11434/api/chat
        │
        ▼
 ChatResponse ──.content()──▶ "안녕하세요! 무엇을 도와드릴까요?"
```

- `prompt()` ~ `user()`: **무엇을 보낼지** 조립하는 단계 (아직 호출하지 않음)
- `call()`: **실제 호출** 시점
- `content()`: 응답에서 **무엇을 꺼낼지** 정하는 단계

### 4-3. 실행해 보기

사전 조건
- 환경변수 `GEMINI_API_KEY` 설정 (또는 `application-local.yaml` + `local` 프로필)
- Ollama 실행 중 (`ollama list`로 `gemma3`가 있는지 확인)

```
http://localhost:8080/basic/gemini?q=안녕
http://localhost:8080/basic/ollama?q=안녕
```

### 4-4. 관찰 포인트 🔍
- 같은 질문에 대한 두 모델의 **답변 품질과 한국어 자연스러움** 비교
- **응답 속도** 비교: 클라우드(Gemini) vs 내 PC의 하드웨어(Ollama)
- 코드 모양은 같은데 뒤에서 호출되는 모델만 다르다는 점 → Spring AI 추상화의 장점

---

## 5. 실습 2~4: system 메시지 / temperature / 응답 전체

```java
@GetMapping("/gemini")
public String gemini(@RequestParam String q) {
    ChatResponse chatResponse = gemini.prompt()
            .system("너는 초등학생에게 설명하는 선생님이야. 3줄 이내로 답해.")  // 실습 2
            .user(q)
            .options(ChatOptions.builder().temperature(0.0))                // 실습 3
            .call()
            .chatResponse();                                                // 실습 4

    Usage usage = chatResponse.getMetadata().getUsage();          // 토큰 사용량
    String text = chatResponse.getResult().getOutput().getText(); // 답변 텍스트
    return text;
}
```

### 실습 2: system 메시지로 역할 정하기

- `system`은 AI가 **어떤 캐릭터로, 어떤 규칙으로** 답할지 정한다.
- 같은 질문이라도 system 메시지만 바꾸면 말투, 길이, 관점이 완전히 달라진다.

🧪 **실험해 보기**: `q=블랙홀이 뭐야`로 고정하고 system만 바꿔 보기
- "너는 초등학생에게 설명하는 선생님이야. 3줄 이내로 답해."
- "너는 천체물리학 교수야. 전문 용어를 써서 답해."
- "너는 해적이야. 해적 말투로 답해."

### 실습 3: temperature로 답변 성격 바꾸기

- 2.0에서는 `.options()`에 **Builder를 그대로** 넘긴다. (`.build()`를 호출하지 않음)
  ```java
  .options(ChatOptions.builder().temperature(0.0))
  ```

| temperature | 특징 | 어울리는 용도 |
|---|---|---|
| 0.0 ~ 0.3 | 매번 거의 같은 답, 정확하고 보수적 | 요약, 분류, 데이터 추출, 코드 |
| 0.7 내외 | 균형 | 일반 대화 |
| 1.0 이상 | 다양하고 창의적, 가끔 엉뚱함 | 아이디어, 글쓰기, 작명 |

🧪 **실험해 보기**: `q=고양이 이름 하나 지어줘`를 temperature `0.0`과 `1.5`로 각각 5번씩 호출해서 비교

### 실습 4: 응답 전체(`ChatResponse`) 보기

- `.content()` → `String` (텍스트만)
- `.chatResponse()` → `ChatResponse` (텍스트 + 메타데이터)
- `Usage`에서 확인할 수 있는 것: 입력(prompt) 토큰, 출력(completion) 토큰, 전체 토큰

🧪 **실험해 보기**: `usage`를 로그로 찍어 보고 확인하기
- system 메시지를 길게 쓰면 **입력 토큰**이 늘어나는지
- "3줄 이내로"라는 지시가 **출력 토큰**을 줄여 주는지

---

## 6. 자주 하는 실수 ⚠️

| 증상 | 원인 / 해결 |
|---|---|
| 앱 시작 시 `ChatModel` 빈이 여러 개라는 에러 | `spring.ai.chat.client.enabled: false` 누락 |
| `ChatClient` 주입 시 빈이 2개라는 에러 | `@Qualifier` 누락 |
| `@RequiredArgsConstructor`를 썼는데 `@Qualifier`가 무시됨 | Lombok은 필드의 `@Qualifier`를 생성자로 복사하지 않는다 → 생성자를 직접 작성하거나 `lombok.config`에 `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` 추가 |
| Gemini 호출 시 401/403 에러 | API 키 미설정 또는 IDE를 재시작하지 않아 환경변수가 반영되지 않음 |
| Gemini 호출 시 429 에러 | 무료 사용량 제한 초과 → 잠시 후 재시도 |
| Ollama 호출 시 Connection refused | Ollama 서버가 꺼져 있음 |
| Ollama 호출 시 model not found | `ollama pull gemma3`를 하지 않음 |
| 모델 이름 설정이 반영되지 않음 | 1.x 방식(`chat.options.model`)을 씀 → 2.0 방식(`chat.model`)으로 변경 |

---

## 7. 확인 문제 ✍️

1. `ChatModel`과 `ChatClient`의 차이는?
2. `system` 메시지와 `user` 메시지의 차이는?
3. 데이터 추출 작업에는 temperature를 높게 할까, 낮게 할까? 왜?
4. `.content()`와 `.chatResponse()`는 언제 각각 쓸까?
5. 스타터를 2개 넣었을 때 `ChatClient`를 직접 만들어야 하는 이유는?
6. `defaultSystem("A")`로 만든 ChatClient에서 요청할 때 `.system("B")`를 쓰면 AI에게 가는 system 메시지는? (실습 5)

<details>
<summary>정답 보기</summary>

1. `ChatModel`은 실제 AI와 통신하는 저수준 객체이고, `ChatClient`는 이를 감싸서 Fluent API로 편하게 쓰게 해 주는 도구다.
2. `system`은 AI의 역할과 규칙을 정하는 지시이고, `user`는 실제 사용자의 질문이다.
3. 낮게 한다. 매번 일관되고 정확한 결과가 필요하기 때문이다.
4. 답변 텍스트만 필요하면 `.content()`, 토큰 사용량 같은 메타데이터도 필요하면 `.chatResponse()`.
5. `ChatModel` 빈이 2개라 자동 구성이 어느 모델로 `ChatClient.Builder`를 만들지 정할 수 없기 때문이다.
6. "B"만 간다. 요청 값이 기본값을 합치지 않고 교체한다.

</details>

---

## 8. 실습 5: 공통 설정을 ChatClient에 미리 넣기 (`defaultXxx`)

### 8-1. 왜 필요한가

"항상 한국어로 답해", "temperature는 0.0"처럼 **모든 요청에 똑같이 붙는 설정**을
엔드포인트마다 `.system()`, `.options()`로 쓰면 중복이 생기고 빠뜨리기도 쉽다.
이런 설정은 **ChatClient를 만들 때 기본값으로 한 번만** 넣어 둔다.

### 8-2. 코드 (`AiClientConfig`)

```java
@Bean
public ChatClient geminiChatClient(GoogleGenAiChatModel googleGenAiChatModel) {
    return ChatClient.builder(googleGenAiChatModel)
            .defaultSystem("항상 한국말로 답해")                        // 기본 system 메시지
            .defaultOptions(ChatOptions.builder().temperature(0.0))   // 기본 옵션
            .build();
}

@Bean
public ChatClient ollamaChatClient(OllamaChatModel ollamaChatModel) {
    return ChatClient.builder(ollamaChatModel)
            .defaultSystem("항상 한국말로 답해")
            .build();
}
```

자주 쓰는 `defaultXxx`

| 메서드 | 용도 |
|---|---|
| `defaultSystem(...)` | 기본 system 메시지 |
| `defaultOptions(...)` | 기본 모델 옵션 (temperature 등) |
| `defaultUser(...)` | 기본 user 메시지 (잘 안 씀) |
| `defaultAdvisors(...)` | 기본 Advisor (대화 기억, 로깅, RAG 등) → 이후 장에서 다룸 |
| `defaultTools(...)` | 기본 Tool → **tool** 장에서 다룸 |

### 8-3. 실험 1: 기본값이 적용되는지

`/basic/ollama`는 요청에서 `.system()`을 쓰지 않는다. 그래서 `defaultSystem`이 그대로 적용된다.

```
http://localhost:8080/basic/ollama?q=Why is the sky blue?
```

→ 영어로 질문해도 **한국어로 답한다.**

### 8-4. 실험 2: 요청에서 또 지정하면? ⭐ 중요

`/basic/gemini`는 요청 안에서 `.system("너는 초등학생에게 설명하는 선생님이야...")`를 따로 쓴다.

**결론: 기본값과 합쳐지지 않고, 요청 쪽 값으로 "교체"된다.**

Spring AI 2.0.2 소스(`DefaultChatClient`)를 보면 동작이 단순하다.

```java
// prompt()를 호출하면 기본값(defaultSystem 등)이 복사된 요청 객체가 만들어지고,
// 요청에서 .system(text)를 호출하면 그 값을 그냥 덮어쓴다.
public ChatClientRequestSpec system(String text) {
    this.systemText = text;   // 기존(default) 값을 교체
    ...
}

// .options(builder)도 마찬가지로 통째로 교체
public <B extends ChatOptions.Builder<?>> ChatClientRequestSpec options(B customizer) {
    this.optionsCustomizer = customizer;
    ...
}
```

```
defaultSystem("항상 한국말로 답해")
          │  prompt() 호출 시 복사
          ▼
요청 객체 systemText = "항상 한국말로 답해"
          │  .system("너는 초등학생에게 설명하는 선생님이야...")
          ▼
요청 객체 systemText = "너는 초등학생에게 설명하는 선생님이야..."   ← 기본값은 사라짐
```

→ 그래서 `/basic/gemini`에서는 "항상 한국말로 답해"라는 규칙이 **적용되지 않는다.**
   (질문이 한국어면 대체로 한국어로 답하겠지만, 그건 규칙 덕분이 아니라 질문 언어를 따라간 것이다.)

### 8-5. 정리

| 상황 | 결과 |
|---|---|
| 요청에서 지정 안 함 | `defaultXxx` 값 사용 |
| 요청에서 `.system()` 지정 | **요청 값으로 교체** (기본값 무시) |
| 요청에서 `.options()` 지정 | **요청 값으로 교체** (`defaultOptions`에 넣은 값도 무시) |

> 💡 공통 규칙을 반드시 지켜야 한다면, 요청마다 쓰는 system 메시지 안에도 그 규칙을 함께 넣어야 한다.
> 나중에 **prompt** 장에서 system 템플릿에 변수를 넣는 방식(`defaultSystem` + `.system(s -> s.param(...))`)을 배우면,
> 기본 문장은 유지하면서 일부만 바꿀 수 있다.

---

## 9. 다음 단계

- `call()`은 답변이 **다 만들어질 때까지 기다렸다가** 한 번에 받는다.
  ChatGPT처럼 글자가 실시간으로 나오게 하려면 `stream()`을 쓴다. → **streaming** 장
- 질문 안에 변수를 넣어 프롬프트를 재사용하려면 → **prompt** 장 (PromptTemplate)
