# Avatar Module — Avatar Catalog Context

> 실사 기준: 2026-07-31 (`origin/develop`).

## Bounded Context

아바타 리소스 **카탈로그**를 소유하는 지원 도메인. V12 에서 `user` 모듈로부터 분리됐다.

이 BC 는 "어떤 아바타 리소스가 존재하고, 어떤 상태인가" 만 책임진다.
"누가 무엇을 착용 중인가" 는 `user` 의 `ProfileData` 가 갖는다.

## 순수 생산자 원칙

**avatar 는 다른 BC 를 import 하지 않는다.** 역방향(`user`/`app` → `avatar`)만 허용하며,
Gradle 의존 그래프가 이를 컴파일 레벨에서 강제한다. 이 방향성이 깨지면 모듈이 빌드되지 않는다.

```
avatar → common   (이게 전부다)
```

## 핵심 엔티티 · VO

| 요소 | 설명 |
|---|---|
| `AvatarBodyResourceData` | 몸체 리소스 |
| `AvatarFaceResourceData` | 얼굴 리소스 |
| `AvatarBodyUri` · `AvatarFaceUri` · `AvatarIconUri` | URI 값 객체 |
| `LifecycleStatus` | 리소스 생명주기 (발행/회수) |
| `ObtainmentType` | 획득 방식 |

## 포트

| Port | 방향 | 용도 |
|---|---|---|
| `AvatarCatalogQueryUseCase` (in) | user / app → avatar | 피커용 카탈로그 조회 |
| `AvatarAdminCatalogQueryUseCase` (in) | app → avatar | 어드민 카탈로그 조회 |
| `AvatarCatalogCommandUseCase` (in) | app → avatar | 어드민 CRUD (등록·수정·발행·회수) |
| `AvatarStoragePort` (out) | avatar → GCS 어댑터 | 이미지 업로드 추상화 |

`AvatarStoragePort` 의 구현은 `adapter/out/storage/GcsAvatarStorageAdapter` 이고,
버킷/키 경로는 `PFPLAY_AVATAR_BUCKET` · `PFPLAY_AVATAR_GCS_KEY_PATH` 로 주입된다.
키 경로 미지정 시 Application Default Credentials 를 쓴다.

## Application Service

| Service | 역할 |
|---|---|
| `AvatarCatalogQueryService` | 사용자용 카탈로그 조회 |
| `AvatarAdminCatalogQueryService` | 어드민용 조회(비발행 포함) |
| `AvatarCatalogCommandService` | 리소스 생성·수정·상태 전이 |

## 도메인 이벤트

`AvatarResourcePublished` · `AvatarResourceRetired`
→ Administration / User Profile 이 구독한다.

## 주의

- 아이콘 리소스 테이블(`avatar_icon_resource`)은 V12 에서 DROP 됐다. 아이콘 URI 는 값으로만 남는다.
- 신규 회원에게는 기본 아바타가 시드된다. 이 시드가 빠지면 프로필이 빈 아바타로 렌더된다
  (과거 회귀 사례 — PR #313).
