// 애그리게이트·상태 열거형·값객체. Spring·DB 무의존(루트 verifySpringFree + ArchUnit).
dependencies {
    api(project(":platform-core"))

    testImplementation(testFixtures(project(":platform-core")))
}
