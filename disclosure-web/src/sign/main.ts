import '../shared/fonts.css';
import { sign } from '../shared/messages.ko.json';

const root = document.getElementById('root');
if (root) {
  document.title = sign.app.title;
  const h1 = document.createElement('h1');
  h1.textContent = sign.app.title;
  root.replaceChildren(h1);
}
