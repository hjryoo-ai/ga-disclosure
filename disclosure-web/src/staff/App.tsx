// 직원 화면 셸: 로그인(데모 OIDC) → 목록·상세·역할별 화면. 메뉴는 역할로 숨기지 않는다 — 역할은 서버(identity_link)만 알고, 칸이 없으면 404다.
import { BrowserRouter, Link, Route, Routes } from 'react-router';
import { staff } from '../shared/messages.ko.json';
import { AuthProvider, useAuth } from './auth/AuthProvider';
import { CollectionRatesPage } from './pages/CollectionRatesPage';
import { CreateDisclosurePage } from './pages/CreateDisclosurePage';
import { CustomerRegisterPage } from './pages/CustomerRegisterPage';
import { DisclosureDetailPage } from './pages/DisclosureDetailPage';
import { DisclosureListPage } from './pages/DisclosureListPage';
import { FlagsPage } from './pages/FlagsPage';
import { JobsPage } from './pages/JobsPage';
import { LegalHoldsPage } from './pages/LegalHoldsPage';
import { VocabularyProvider, useVocabularyOutcome } from './api/vocabulary';
import { ProblemView } from './components/ProblemView';

function Login() {
  const { login, pending, failed } = useAuth();
  return (
    <main id="main">
      <h1>{staff.app.title}</h1>
      <p>{staff.login.intro}</p>
      <button type="button" onClick={login} disabled={pending}>{staff.login.button}</button>
      <div aria-live="polite">{pending ? <p>{staff.login.pending}</p> : failed ? <p role="alert">{staff.login.failed}</p> : null}</div>
    </main>
  );
}

/** 어휘를 못 읽었으면(예: 룰 없는 테넌트 503) 서버 응답을 그대로 보인다 — 사유 칸의 선택지가 빈다. */
function VocabularyStatus() {
  const v = useVocabularyOutcome();
  return v === null || v.ok ? null : <div className="panel" data-testid="vocabulary-problem"><ProblemView status={v.status} problem={v.problem} /></div>;
}

function Shell() {
  const { session, logout } = useAuth();
  if (session === null) return <Login />;
  return (
    <VocabularyProvider>
      <header className="top">
        <p className="who">{staff.app.signedInAs} <code>{session.claims.tenant}</code> · <code>{session.claims.sub}</code></p>
        <nav aria-label={staff.nav.label}>
          <ul>
            <li><Link to="/staff">{staff.nav.disclosures}</Link></li>
            <li><Link to="/staff/customers/new">{staff.nav.registerCustomer}</Link></li>
            <li><Link to="/staff/disclosures/new">{staff.nav.newDisclosure}</Link></li>
            <li><Link to="/staff/flags">{staff.nav.flags}</Link></li>
            <li><Link to="/staff/legal-holds">{staff.nav.legalHolds}</Link></li>
            <li><Link to="/staff/jobs">{staff.nav.jobs}</Link></li>
            <li><Link to="/staff/collection-rates">{staff.nav.collectionRates}</Link></li>
          </ul>
        </nav>
        <button type="button" onClick={logout}>{staff.app.logout}</button>
      </header>
      <main id="main">
        <VocabularyStatus />
        <Routes>
          <Route path="/" element={<DisclosureListPage />} />
          <Route path="/staff" element={<DisclosureListPage />} />
          <Route path="/staff/customers/new" element={<CustomerRegisterPage />} />
          <Route path="/staff/disclosures/new" element={<CreateDisclosurePage />} />
          <Route path="/staff/disclosures/:id" element={<DisclosureDetailPage />} />
          <Route path="/staff/flags" element={<FlagsPage />} />
          <Route path="/staff/legal-holds" element={<LegalHoldsPage />} />
          <Route path="/staff/jobs" element={<JobsPage />} />
          <Route path="/staff/collection-rates" element={<CollectionRatesPage />} />
          <Route path="*" element={<p>{staff.app.notFound}</p>} />
        </Routes>
      </main>
    </VocabularyProvider>
  );
}

export function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <a className="skip" href="#main">{staff.app.skip}</a>
        <Shell />
      </BrowserRouter>
    </AuthProvider>
  );
}
