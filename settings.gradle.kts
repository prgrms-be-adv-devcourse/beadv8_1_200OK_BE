pluginManagement {
    // 모든 서브프로젝트가 공유하는 빌드 설정(컨벤션 플러그인)
    includeBuild("build-logic")
}

rootProject.name = "ok"

include("common", "delivery", "media", "member", "monolith", "order", "payout", "product", "refund", "wallet")
