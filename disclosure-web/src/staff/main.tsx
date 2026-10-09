import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../shared/fonts.css';
import { staff } from '../shared/messages.ko.json';

const root = document.getElementById('root');
if (root) {
  document.title = staff.app.title;
  createRoot(root).render(
    <StrictMode>
      <h1>{staff.app.title}</h1>
    </StrictMode>,
  );
}
