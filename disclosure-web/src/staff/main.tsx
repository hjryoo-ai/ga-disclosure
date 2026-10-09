import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../shared/fonts.css';
import { staff } from '../shared/messages.ko.json';
import { App } from './App';
import './staff.css';

const root = document.getElementById('root');
if (root) {
  document.title = staff.app.title;
  createRoot(root).render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}
