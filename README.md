# NightDeX

삼성 갤럭시 DeX 모드에서 **외부 디스플레이를 어둡게** 조절하는 앱.

- **고정 모드** — 슬라이더로 밝기를 정하면 값이 저장되고, 앱을 다시 켜면 바로 그 밝기로 복원됩니다.
- **자동 모드** — 폰의 조도 센서를 읽어 주변이 어두우면 모니터도 어둡게, 밝으면 원래
  밝기에 가깝게 맞춥니다. 야간 주행 중 헤드라이트·가로등에 화면이 번쩍이지 않도록
  강하게 평활화되어 있습니다 (아래 참고).
- 외부 디스플레이가 여러 대면 디스플레이별로 값을 따로 기억합니다. 폰 화면은 건드리지 않습니다.
- 빠른 설정 타일로 한 번에 켜고 끌 수 있습니다.
- 화면 맨 아래 **종료** 버튼은 디밍을 끄고 오버레이를 내린 뒤 앱을 닫습니다. 창만 닫으면 포그라운드 서비스가 남아 디밍이 계속됩니다.

## 동작 방식과 한계 (중요)

Android 공개 API로는 외부 모니터의 **실제 백라이트를 바꿀 수 없습니다**
(`DisplayManager.setBrightness`는 시스템 권한 전용, DDC/CI는 접근 불가).
그래서 이 앱은 해당 디스플레이 위에 **반투명 검정 오버레이**를 띄워 어둡게 만듭니다.

- 밝기 100% = 오버레이 없음. 그보다 **밝게 만들 수는 없습니다.**
- 오버레이 창에 `FLAG_NOT_TOUCHABLE`을 주므로 터치·마우스 클릭은 그대로 아래 창으로 통과합니다.
- 단, Android 12부터 일반 오버레이(`TYPE_APPLICATION_OVERLAY`)의 불투명도가 **0.8을 넘으면
  통과하던 입력이 차단**됩니다. 그래서 기본 모드의 최대 어둡기는 alpha 0.8로 캡되어 있습니다.
- 더 어둡게 하려면 **"더 어둡게 (접근성)"** 스위치를 켜세요. 접근성 오버레이
  (`TYPE_ACCESSIBILITY_OVERLAY`)는 신뢰 윈도우라 0.8 제한이 없어 alpha 0.95까지 갑니다.
  접근성 서비스는 화면 내용을 읽지 않습니다(`canRetrieveWindowContent="false"`).
- `filterTouchesWhenObscured`를 쓰는 보안 민감 화면(권한 대화상자, 일부 금융 앱)은
  오버레이가 떠 있으면 입력을 무시할 수 있습니다. 그럴 땐 빠른 설정 타일로 잠깐 끄세요.
- 상태바/키가드 같은 시스템 윈도우는 오버레이보다 위에 그려져 어두워지지 않습니다.

## 빌드

이 저장소는 로컬에 설치된 SDK를 씁니다 (`local.properties`의 `sdk.dir`).

```bash
export JAVA_HOME=~/.sdkman/candidates/java/current   # JDK 17+
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

설치:

```bash
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- `compileSdk` / `targetSdk` 35, `minSdk` **30**
  (`Context.createWindowContext()`가 API 30부터라 특정 디스플레이에 창을 붙이려면 필요)

## 처음 실행할 때

1. 앱을 열고 **"권한 설정 열기"** → *다른 앱 위에 표시* 허용.
2. **밝기 조절 사용** 스위치 ON.
3. DeX 연결 후 슬라이더로 밝기 조절. 값은 자동 저장되고 다음 실행 때 즉시 적용됩니다.
4. (선택) 0.8보다 더 어둡게 하려면 **더 어둡게 (접근성)** ON →
   *설정 > 접근성 > 설치된 앱 > NightDeX 딥 디밍* 켜기.

## 구조

| 파일 | 역할 |
| --- | --- |
| `DimEngine.kt` | 오버레이 창, 디스플레이 목록, 조도 센서를 모두 관리하는 단일 상태 소유자 |
| `Settings.kt` | 저장 설정(`Prefs`), 밝기 레벨 ↔ alpha 변환, 디스플레이 정보 |
| `DimService.kt` | 포그라운드 서비스. 기본 `OverlayHost` (`TYPE_APPLICATION_OVERLAY`, alpha ≤ 0.8) |
| `DimAccessibilityService.kt` | 선택적 `OverlayHost` (`TYPE_ACCESSIBILITY_OVERLAY`, alpha ≤ 0.95) |
| `DimTileService.kt` | 빠른 설정 타일 |
| `MainActivity.kt` | Compose UI |

`OverlayHost`가 두 백엔드의 공통 인터페이스이고, `DimEngine.chooseHost()`가
"더 어둡게" 설정에 따라 우선순위가 높은 쪽을 고릅니다. 호스트가 바뀌면 기존
오버레이를 모두 내리고 새 호스트로 다시 붙입니다.

### 자동 모드 필터 (차량 주행 기준으로 튜닝)

용도가 야간 차량 네비게이션이라, 조도 센서를 그대로 따라가면 마주 오는 차의
헤드라이트나 가로등이 훑고 지나갈 때마다 화면이 번쩍입니다. 3단으로 걸러냅니다.

1. **중앙값 필터** (`MEDIAN_WINDOW = 5`) — 한두 샘플짜리 순간 스파이크를 아예 제거합니다.
2. **비대칭 지수 평활** — 샘플 개수가 아니라 실제 경과 시간 기준이라 센서 보고 주기가
   달라져도 동작이 같습니다.
   - `TAU_RISE_SEC = 25` — **밝아지는 방향은 아주 느리게.** 1초짜리 헤드라이트는
     필터를 몇 %밖에 못 움직이지만, 터널 탈출처럼 수십 초 지속되는 변화는 온전히 따라갑니다.
   - `TAU_FALL_SEC = 5` — **어두워지는 방향은 조금 빠르게.** "밤에 너무 밝다"가
     해결하려는 문제이므로 어두워지는 건 덜 미뤄도 됩니다.
3. **레벨→alpha 단계** — 필터가 이미 느리므로 1% 단위로 반영하고, 이 폭은 오버레이
   애니메이션 임계값(0.06) 아래라 프레임 단위로는 변화가 보이지 않습니다.

첫 샘플은 평활 없이 바로 채택하고, 자동 모드로 전환할 때 필터 이력을 비웁니다
(`resetAutoFilter()`). 켜자마자 25초를 기다릴 일은 없습니다.

조도→밝기 매핑 자체는 로그 스케일입니다 (0.5 lx ~ 2000 lx). 체감 밝기가 lux 값
자체가 아니라 자릿수를 따라가기 때문입니다.

반응 속도가 취향에 안 맞으면 `DimEngine.kt` 맨 위의 상수만 고치면 됩니다.
UI의 "센서 원값" 표시로 필터 전/후를 같이 볼 수 있습니다.

## 아직 실기기에서 검증하지 못한 부분

빌드 환경에 연결된 갤럭시 기기가 없어 아래는 **DeX 실기기 확인이 필요**합니다.

- DeX의 외부 디스플레이가 `DisplayManager.getDisplays()`에 non-default 디스플레이로
  잡히는지, 그 위에 `TYPE_APPLICATION_OVERLAY`를 붙일 수 있는지.
- `createDisplayContext(display).createWindowContext(TYPE_ACCESSIBILITY_OVERLAY, null)`이
  접근성 서비스 컨텍스트에서 보조 디스플레이에 대해 허용되는지.
  실패하면 `DimEngine`이 예외를 잡아 로그를 남기고 UI에 "오버레이를 붙이지 못했습니다"를
  표시하며, 일반 오버레이 호스트로 계속 동작합니다.
- DeX에서 폰 화면이 꺼져 있을 때 조도 센서가 계속 값을 보고하는지
  (안 되면 자동 모드가 마지막 값에 머뭅니다).
- 차량 거치대에 꽂았을 때 폰 조도 센서가 가려지지 않는지 — 가려지면 자동 모드가
  항상 최소 밝기로 붙습니다. UI의 "센서 원값"으로 확인할 수 있습니다.

확인은 `adb logcat -s DimEngine` 으로 할 수 있습니다.
