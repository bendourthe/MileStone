(() => {
  'use strict';
  const page = document.querySelector('[data-dv-page]');
  const deck = document.querySelector('[data-dv-deck]');
  document.documentElement.classList.add('js-ready');
  document.querySelector('#reading-theme').addEventListener('change', event => {
    page.dataset.readingTheme = event.target.value;
  });
  document.querySelector('#reading-depth').addEventListener('change', event => {
    page.dataset.readingDepth = event.target.value;
  });
  document.querySelector('#presentation-depth').addEventListener('change', event => {
    deck.dataset.depth = event.target.value;
  });
  document.querySelector('#presentation-theme').addEventListener('change', event => {
    deck.querySelectorAll('[data-dv-slide]').forEach(slide => {
      slide.dataset.theme = event.target.value === 'mixed' ? slide.dataset.defaultTheme : event.target.value;
    });
    const current = deck.querySelector('[data-dv-slide]:not([hidden])');
    if (current) deck.dataset.theme = current.dataset.theme;
  });
  const links = [...document.querySelectorAll('.contents a')];
  if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => {
      const visible = entries.filter(entry => entry.isIntersecting)
        .sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
      if (!visible.length) return;
      const target = '#' + visible[0].target.id;
      links.forEach(link => {
        if (link.getAttribute('href') === target) link.setAttribute('aria-current', 'location');
        else link.removeAttribute('aria-current');
      });
    }, { rootMargin: '-10% 0px -65% 0px', threshold: 0 });
    document.querySelectorAll('.source-document > .source-section').forEach(section => observer.observe(section));
  }
})();
