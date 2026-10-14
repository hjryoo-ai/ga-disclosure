// 룰 어휘(Phase 8 G9, 승인 Q8): 로그인 뒤 한 번 읽어(getRuleVocabulary) 사유·해소 코드 칸을 선택 입력으로 만든다 — 코드와 표기만, 룰 순서 그대로,
// 기본 선택 없음. 판정은 지금처럼 서버가 한다: 목록을 못 읽으면 선택지가 없을 뿐 화면은 요청을 막지 않고, 서버의 거부를 그대로 보인다.
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import type { components } from '../../gen/disclosure-api';
import { outcome, useApi, type Outcome } from './useApi';

export type Vocabulary = components['schemas']['RuleVocabulary'];
export type CodeLabel = Vocabulary['reasonCodes'][number];

const VocabularyContext = createContext<Outcome<Vocabulary> | null>(null);

export function VocabularyProvider({ children }: { children: ReactNode }) {
  const api = useApi();
  const [vocabulary, setVocabulary] = useState<Outcome<Vocabulary> | null>(null);
  useEffect(() => {
    void api.GET('/api/v1/rules/vocabulary').then((r) => { setVocabulary(outcome(r)); });
  }, [api]);
  return <VocabularyContext.Provider value={vocabulary}>{children}</VocabularyContext.Provider>;
}

/** 읽기 결과(아직이면 null). */
export function useVocabularyOutcome(): Outcome<Vocabulary> | null {
  return useContext(VocabularyContext);
}

/** 한 목록(읽기 전·실패면 빈 목록 — 선택지가 없을 뿐, 서버 판정은 그대로). */
export function useCodes(pick: (v: Vocabulary) => readonly CodeLabel[]): readonly CodeLabel[] {
  const v = useContext(VocabularyContext);
  return v !== null && v.ok ? pick(v.data) : [];
}
