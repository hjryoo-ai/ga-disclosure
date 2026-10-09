// 전송 계층 사실(HTTP 응답 코드)의 유일한 판독 지점. 업무 상태가 아니다 — 화면의 `.status` 비교 금지 규칙(screenLogic.test, G3)의 허용 목록 한 항목.
/** 422 = 업무 거부(서버가 거부 코드를 실었다). */
export function isBusinessRejection(response: Response): boolean {
  return response.status === 422;
}
