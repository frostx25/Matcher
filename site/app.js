const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
if (!reducedMotion) {
  window.addEventListener('pointermove', (event) => {
    document.documentElement.style.setProperty('--mx', `${event.clientX}px`);
    document.documentElement.style.setProperty('--my', `${event.clientY}px`);
  }, { passive: true });
}

document.querySelector('#year').textContent = new Date().getFullYear();
