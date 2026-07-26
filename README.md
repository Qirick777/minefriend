# 워든걸 (WardenGirl)

Minecraft 1.20.1 / Forge / Java 17 동료형 몹 모드.

**현재 페이즈: 1차 — 애니메이션 뼈대 전용.**
AI, 전투 판정, 데미지, 파티클, 사운드, 실제 이동은 2차 범위이며 이 페이즈에 존재하지 않는다.

사양서: [`docs/워든걸_1차설계서.md`](docs/워든걸_1차설계서.md) — 이 문서가 1차의 유일한 사양이다.

## 확정 버전

| 항목 | 확정값 | 확인 방법 |
|---|---|---|
| Minecraft | 1.20.1 | — |
| Forge | **1.20.1-47.4.22** | `promotions_slim.json` 의 `1.20.1-latest` |
| GeckoLib | **4.8.4** (`software.bernie.geckolib:geckolib-forge-1.20.1`) | Cloudsmith `maven-metadata.xml` 의 `<release>` |
| 매핑 | official / 1.20.1 | — |
| Java | 17 | Gradle toolchain |

GeckoLib 저장소: `https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/`

배포된 GeckoLib 아티팩트는 프로덕션 jar(SRG 난독화 멤버명)이므로 `fg.deobf()` 를 통과시켜야 한다.

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
| T0 | 프로젝트 초기화 | 구현 완료 — 사람 확인 대기 |
| T1 | 모델 · 렌더러 · 축 검증 | 미착수 |
| T2 | C1 상시 레이어 + 파라미터 시스템 | 미착수 |
| T3 | headgear 스프링 물리 | 미착수 |
| T4 | 시선 추적 | 미착수 |
| T5 | C2 걷기 | 미착수 |
| T6 | C3 랜덤 idle 5종 | 미착수 |
| T7 | 기본 공격 모션 | 미착수 |
| T8 | 소닉붐 모션 | 미착수 |
| T9 | 돌진 모션 | 미착수 |
