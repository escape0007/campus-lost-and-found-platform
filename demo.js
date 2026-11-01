(() => {
  'use strict';
  const tabs = [...document.querySelectorAll('.demo-tabs [role="tab"]')];
  const panels = tabs.map(tab => document.getElementById(tab.getAttribute('aria-controls')));
  const controls = document.querySelector('.step-controls');
  const previous = document.getElementById('previous-step');
  const next = document.getElementById('next-step');
  const progress = document.getElementById('step-progress');
  if (!tabs.length || panels.some(panel => !panel) || !controls || !previous || !next || !progress) return;
  let selected = 0;

  function showStep(index, moveFocus = false) {
    selected = Math.max(0, Math.min(index, tabs.length - 1));
    tabs.forEach((tab, i) => {
      tab.setAttribute('aria-selected', String(i === selected));
      tab.tabIndex = i === selected ? 0 : -1;
      panels[i].hidden = i !== selected;
    });
    previous.disabled = selected === 0;
    next.textContent = selected === tabs.length - 1 ? '重新浏览 ↺' : '下一步 →';
    progress.textContent = `${String(selected + 1).padStart(2, '0')} / ${String(tabs.length).padStart(2, '0')} · ${tabs[selected].textContent.slice(2).trim()}`;
    if (moveFocus) tabs[selected].focus();
  }

  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => showStep(index));
    tab.addEventListener('keydown', event => {
      let destination;
      if (event.key === 'ArrowRight') destination = (index + 1) % tabs.length;
      if (event.key === 'ArrowLeft') destination = (index - 1 + tabs.length) % tabs.length;
      if (event.key === 'Home') destination = 0;
      if (event.key === 'End') destination = tabs.length - 1;
      if (destination !== undefined) {
        event.preventDefault();
        showStep(destination, true);
      }
    });
  });
  previous.addEventListener('click', () => showStep(selected - 1));
  next.addEventListener('click', () => showStep(selected === tabs.length - 1 ? 0 : selected + 1));
  showStep(0);
  controls.hidden = false;
})();
