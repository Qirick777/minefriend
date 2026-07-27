# 워든걸 (WardenGirl)

Minecraft 1.20.1 / Forge / Java 17 동료형 몹 모드.

**현재 페이즈: 1차 — 애니메이션 뼈대 전용.**
AI, 전투 판정, 데미지, 파티클, 사운드, 실제 이동은 2차 범위이며 이 페이즈에 존재하지 않는다.

## 설계서 정본

**정본은 [`docs/워든걸_1차설계서.md`](docs/워든걸_1차설계서.md) 하나뿐이다.** 1차의 유일한 사양이다.

**사본을 만들지 마라.** 리포지토리 다른 위치(특히 루트)에 설계서를 두지 않는다. 실제로 초안 사본이 루트에 올라온 적이 있고, 그 사본은 이미 정정된 값(`이동속도 0.3`)을 되살릴 뻔했다. 개정 이력은 정본의 Part 11 한 곳에만 쌓인다.

## 확정 버전

| 항목 | 확정값 | 확인 방법 |
|---|---|---|
| Minecraft | 1.20.1 | — |
| Forge | **1.20.1-47.4.10** | `promotions_slim.json` 의 `1.20.1-recommended` |
| GeckoLib | **4.8.4** (`software.bernie.geckolib:geckolib-forge-1.20.1`) | Cloudsmith `maven-metadata.xml` 의 `<release>` |
| 매핑 | official / 1.20.1 | — |
| Java | 17 | Gradle toolchain |

GeckoLib 저장소: `https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/`

배포된 GeckoLib 아티팩트는 프로덕션 jar(SRG 난독화 멤버명)이므로 `fg.deobf()` 를 통과시켜야 한다.

## 환경 재구성 시 반드시 필요한 빌드 설정 2건

이 워크스페이스를 다시 세팅할 때 아래 두 설정이 없으면 같은 지점에서 막힌다.
둘 다 T0에서 실제로 발생시킨 뒤 원인을 확인하고 넣은 것이다.

### 1. GeckoLib mixin refmap 리매핑 — **없으면 클라이언트가 크래시한다**

`build.gradle` 의 `minecraft.runs.configureEach`:

```groovy
property 'mixin.env.remapRefMap', 'true'
property 'mixin.env.refMapRemappingFile', "${projectDir}/build/createSrgToMcp/output.srg"
```

**이유.** GeckoLib 은 프로덕션 refmap(`geckolib.refmap.json`)을 함께 배포하는데, 그 안의
멤버 이름은 SRG 다 — 예: `TextureManager.m_118506_`. 그런데 이 프로젝트는 매핑 채널이
`official`(설계서 Part 3.3)이라 개발 런타임에서 같은 메서드의 이름은 `getTexture` 다.
리매핑 파일이 없으면 Mixin 이 SRG 이름을 그대로 찾다가 대상을 못 찾고, GeckoLib 의
`client.TextureManagerMixin` 이 하드 실패한다.

```
InvalidInjectionException: Critical injection failure: @Inject annotation on
wrapAnimatableTexture could not find any targets matching
'...TextureManager;m_118506_(...)' Using refmap geckolib.refmap.json
```

게임은 `Initializing game` 단계에서 죽는다. **모드 셋업이 돌기 전이라 우리 코드에는
아무 로그도 남지 않는다** — 그래서 원인이 GeckoLib 쪽이라는 게 잘 안 보인다.
`createSrgToMcp` 태스크가 정확히 이 SRG → official 매핑을 생성해준다.

`prepareRun*` 태스크가 `createSrgToMcp` 에 의존하도록도 걸어두었다. 리매핑 파일이
런 시작 전에 존재해야 하기 때문이다. (ForgeGradle 이 `prepareRun*` 을 자신의
`afterEvaluate` 에서 생성하므로 `tasks.named` 로는 잡히지 않는다. `tasks.matching` 을 쓴다.)

### 2. `processResources` 인코딩

```groovy
filteringCharset = 'UTF-8'
```

없으면 `expand()` 가 플랫폼 기본 인코딩으로 읽고 써서 `mods.toml` / `pack.mcmeta` 의
비ASCII 바이트가 깨진다. (em-dash 가 `???` 로 나오는 것을 확인했다. `mod_description` 에
한글을 넣으면 같은 방식으로 깨진다.) 필터를 타지 않는 `ko_kr.json` 은 영향 없다.

## 빌드

```bash
./gradlew build          # jar 생성
./gradlew runClient      # 개발 클라이언트 실행
```

Java 17 이 필요하다. JDK 17 이 기본이 아닌 환경에서는:

```bash
JAVA_HOME=/path/to/jdk-17 ./gradlew build
```

## 태스크 진행 상황

Part 5 태스크 분할 기준. 각 태스크는 **사람의 눈**으로 판정되며, 확인 없이 다음으로 진행하지 않는다.

| 태스크 | 내용 | 상태 |
|---|---|---|
| T0 | 프로젝트 초기화 | **승인 완료** |
| T1 | 모델 · 렌더러 · 축 검증 | 구현 완료 — 사람 확인 대기 |
| T2 | C1 상시 레이어 + 파라미터 시스템 | 미착수 |
| T3 | headgear 스프링 물리 | 미착수 |
| T4 | 시선 추적 | 미착수 |
| T5 | C2 걷기 | 미착수 |
| T6 | C3 랜덤 idle 5종 | 미착수 |
| T7 | 기본 공격 모션 | 미착수 |
| T8 | 소닉붐 모션 | 미착수 |
| T9 | 돌진 모션 | 미착수 |

## 사전 통지된 사양 결함 (T5에서 처리)

**바운스 / 걷기 위상 맥놀이.** 설계서 4.3.2 의 바운스는 `abs(sin(t * 7.2))` 로,
주기가 25tick 이라 적혀 있지만 `abs` 때문에 실질 주기는 **12.5tick** 이다.
4.4.2 걷기의 반보는 **13tick** 이다. 두 값이 가깝지만 같지 않아서, 걷는 동안
위상이 맞았다 어긋났다 반복하는 맥놀이가 생긴다. 4.4.2 는 "바운스 위상 동기" 를
요구하지만 현재 수치로는 동기가 성립하지 않는다.

**T5 도착 시 이 문제를 먼저 보고한다. 해결안은 사람이 정한다. 미리 손대지 않는다.**
