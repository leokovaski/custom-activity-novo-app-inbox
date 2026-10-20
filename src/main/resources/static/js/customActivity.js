'use strict';

/**
 * Comunicação com o Journey Builder via Postmonger.
 * Fluxo: ready -> initActivity + requestedInteraction
 *        -> (usuário configura) -> clickedNext -> updateActivity
 */
if (typeof Postmonger === 'undefined') {
  // Sem Postmonger o Journey Builder nunca recebe o "ready" e o loading não some.
  // Este guard torna a falha visível em vez de silenciosa.
  document.body.innerHTML =
    '<p style="padding:24px;color:#c23934;font-family:sans-serif">' +
    'Erro: postmonger.min.js não carregou. Verifique se o arquivo existe em /js/.</p>';
  throw new Error('Postmonger não carregado');
}

// ponytail: catálogo fixo; trocar pelos nomes de ícone que o app reconhece.
var ICONS = [
  ['bell', '🔔 Sino'], ['gift', '🎁 Presente'], ['star', '⭐ Estrela'], ['tag', '🏷️ Oferta'],
  ['info', 'ℹ️ Informação'], ['warning', '⚠️ Alerta'], ['calendar', '📅 Calendário'],
  ['card', '💳 Cartão'], ['truck', '🚚 Entrega'], ['heart', '❤️ Favorito'],
  ['check', '✅ Concluído'], ['clock', '⏰ Lembrete']
];

// Campos do formulário: id do elemento == nome no inArguments
// (midiaTipo/imagemUrl ficam ocultos até a imagem entrar; urlTipo é radio)
var FIELDS = ['title', 'message', 'midiaTipo', 'icone', 'imagemUrl',
  'cta', 'url', 'dataInicio', 'dataFim'];

// Enviados sem aparecer para o usuário
var FIXED = { classificacao: 'Padrão', tipoNotificacao: 'Novidade' };

// Limite de caracteres por campo. Variáveis {{Event.DE.campo}} não contam
// (o tamanho real só é conhecido no envio).
var LIMITS = { title: 28, message: 160, cta: 30 };
var VAR_RE = /\{\{[^}]*\}\}/g;

// Campos da Data Extension de entrada da jornada (via requestSchema)
var schemaFields = [];

var connection = new Postmonger.Session();
var activity = null;
var journeyName = '';
var journeyId = '';

$onReady();

function $onReady() {
  connection.on('initActivity', onInitActivity);
  connection.on('clickedNext', onClickedNext);
  connection.on('requestedInteraction', onRequestedInteraction);
  connection.on('requestedSchema', onRequestedSchema);

  var icone = document.getElementById('icone');
  ICONS.forEach(function (ic) {
    var opt = document.createElement('option');
    opt.value = ic[0];
    opt.textContent = ic[1];
    icone.appendChild(opt);
  });

  connection.trigger('ready');
  connection.trigger('requestInteraction');
  connection.trigger('requestSchema');

  toggleDoneButton(false);
  FIELDS.forEach(function (id) {
    document.getElementById(id).addEventListener('input', validateForm);
    document.getElementById(id).addEventListener('change', validateForm);
  });
  document.getElementById('midiaTipo').addEventListener('change', toggleMidia);
  urlTipoInputs().forEach(function (r) { r.addEventListener('change', toggleUrl); });
  each('.btn-text', function (btn) {
    btn.addEventListener('click', function () { togglePicker(btn.getAttribute('data-target')); });
  });
  each('.var-picker', function (sel) {
    sel.addEventListener('change', function () {
      if (sel.value) insertChip(sel.getAttribute('data-target'), sel.value);
      sel.classList.add('hidden');
    });
  });
  each('.rich', function (ed) {
    ed.addEventListener('input', function () { syncEditor(ed); });
    ed.addEventListener('keydown', function (e) {
      if (e.key !== 'Enter') return;
      e.preventDefault();
      if (!ed.getAttribute('data-single')) document.execCommand('insertText', false, '\n');
    });
    // Cola sempre como texto puro (sem formatação)
    ed.addEventListener('paste', function (e) {
      e.preventDefault();
      document.execCommand('insertText', false, e.clipboardData.getData('text/plain'));
    });
    ed.addEventListener('click', function (e) {
      if (e.target.classList.contains('chip-x')) { e.target.parentNode.remove(); syncEditor(ed); }
    });
  });
  toggleMidia();
  toggleUrl();
}

function each(selector, fn) {
  Array.prototype.slice.call(document.querySelectorAll(selector)).forEach(fn);
}

function picker(target) {
  return document.querySelector('.var-picker[data-target="' + target + '"]');
}

function togglePicker(target) {
  var sel = picker(target);
  sel.innerHTML = '';
  var first = document.createElement('option');
  first.value = '';
  first.textContent = schemaFields.length ? 'Selecione o campo da Data Extension…'
    : '(nenhum campo disponível — a jornada precisa de uma Data Extension de entrada)';
  sel.appendChild(first);
  schemaFields.forEach(function (f) {
    var opt = document.createElement('option');
    opt.value = f.key;
    opt.textContent = f.name;
    sel.appendChild(opt);
  });
  sel.classList.toggle('hidden');
  if (!sel.classList.contains('hidden')) sel.focus();
}

// ---------------------------------------------------------------------------
// Editor com variáveis como "tags" (pills). O valor serializado — texto com
// {{Event.DE.campo}} — fica no input oculto de mesmo nome do campo.
// ---------------------------------------------------------------------------
function editorOf(id) {
  return document.querySelector('.rich[data-for="' + id + '"]');
}

function chipLabel(key) {
  var found = schemaFields.filter(function (f) { return f.key === key; })[0];
  if (found) return found.name;
  var parts = key.split('.');
  return parts[parts.length - 1];
}

function makeChip(key) {
  var chip = document.createElement('span');
  chip.className = 'chip';
  chip.contentEditable = 'false';
  chip.setAttribute('data-key', key);
  chip.title = '{{' + key + '}}';
  chip.appendChild(document.createTextNode(chipLabel(key)));
  var x = document.createElement('button');
  x.type = 'button';
  x.className = 'chip-x';
  x.setAttribute('aria-label', 'Remover');
  x.textContent = '×';
  chip.appendChild(x);
  return chip;
}

// DOM do editor -> string com {{...}}
function serializeEditor(ed) {
  var out = '';
  Array.prototype.slice.call(ed.childNodes).forEach(function (n) {
    if (n.nodeType === 3) out += n.nodeValue;
    else if (n.classList && n.classList.contains('chip')) out += '{{' + n.getAttribute('data-key') + '}}';
    else if (n.nodeName === 'BR') out += '\n';
    else out += n.textContent;
  });
  return out;
}

// string com {{...}} -> DOM do editor
function renderEditor(id) {
  var ed = editorOf(id);
  var text = document.getElementById(id).value;
  ed.innerHTML = '';
  var last = 0, m;
  VAR_RE.lastIndex = 0;
  while ((m = VAR_RE.exec(text)) !== null) {
    if (m.index > last) ed.appendChild(document.createTextNode(text.slice(last, m.index)));
    ed.appendChild(makeChip(m[0].slice(2, -2)));
    last = m.index + m[0].length;
  }
  if (last < text.length) ed.appendChild(document.createTextNode(text.slice(last)));
}

function syncEditor(ed) {
  document.getElementById(ed.getAttribute('data-for')).value = serializeEditor(ed);
  validateForm();
}

function insertChip(id, key) {
  var ed = editorOf(id);
  var chip = makeChip(key);
  var sel = window.getSelection();
  var range = sel.rangeCount && ed.contains(sel.getRangeAt(0).startContainer)
    ? sel.getRangeAt(0) : null;
  if (range) {
    range.deleteContents();
    range.insertNode(chip);
  } else {
    ed.appendChild(chip);
  }
  // caret logo após a tag
  var after = document.createRange();
  after.setStartAfter(chip);
  after.collapse(true);
  sel.removeAllRanges();
  sel.addRange(after);
  ed.focus();
  syncEditor(ed);
}

// Comprimento sem as variáveis {{...}}
function lengthOf(id) {
  return val(id).replace(VAR_RE, '').length;
}

function withinLimit(id) {
  return lengthOf(id) <= LIMITS[id];
}

function renderCounters() {
  Object.keys(LIMITS).forEach(function (id) {
    var c = document.querySelector('.counter[data-for="' + id + '"]');
    if (!c) return;
    c.textContent = lengthOf(id) + '/' + LIMITS[id];
    c.classList.toggle('over', !withinLimit(id));
  });
}

function val(id) {
  return document.getElementById(id).value.trim();
}

function urlTipoInputs() {
  return Array.prototype.slice.call(document.querySelectorAll('input[name="urlTipo"]'));
}

function urlTipo() {
  var checked = document.querySelector('input[name="urlTipo"]:checked');
  return checked ? checked.value : 'nenhum';
}

function toggleMidia() {
  var imagem = val('midiaTipo') === 'imagem';
  document.getElementById('grupoIcone').classList.toggle('hidden', imagem);
  document.getElementById('grupoImagem').classList.toggle('hidden', !imagem);
  validateForm();
}

function toggleUrl() {
  document.getElementById('grupoUrl').classList.toggle('hidden', urlTipo() === 'nenhum');
  validateForm();
}

function validateForm() {
  var midiaOk = val('midiaTipo') === 'imagem' ? Boolean(val('imagemUrl')) : Boolean(val('icone'));
  var urlOk = urlTipo() === 'nenhum' || Boolean(val('url'));
  var datasOk = !val('dataInicio') || !val('dataFim') || val('dataInicio') <= val('dataFim');
  var limitesOk = Object.keys(LIMITS).every(withinLimit);
  renderCounters();
  toggleDoneButton(Boolean(val('title') && val('message') && midiaOk && urlOk && datasOk && limitesOk));
}

function toggleDoneButton(enabled) {
  connection.trigger('updateButton', {
    button: 'next',
    text: 'done',
    visible: true,
    enabled: enabled
  });
}

// Schema da Data Extension de entrada: [{ key: "Event.DEAudience-xxx.Campo", name, type }]
function onRequestedSchema(data) {
  var schema = (data && data.schema) || [];
  schemaFields = schema.map(function (f) {
    var parts = String(f.key || '').split('.');
    return { key: f.key, name: f.name || parts[parts.length - 1] };
  }).filter(function (f) { return f.key; });
  renderEditor('title');
  renderEditor('message');
}

function onRequestedInteraction(interaction) {
  if (!interaction) return;
  journeyName = interaction.name || '';
  journeyId = interaction.id || '';
  var el = document.getElementById('journeyName');
  if (el) el.textContent = journeyName || '(não disponível)';
}

function onInitActivity(payload) {
  activity = payload || {};

  var inArguments =
    activity.arguments &&
    activity.arguments.execute &&
    activity.arguments.execute.inArguments
      ? activity.arguments.execute.inArguments
      : [];

  var args = inArguments.reduce(function (acc, arg) {
    Object.keys(arg).forEach(function (k) { acc[k] = arg[k]; });
    return acc;
  }, {});

  // Restaura valores salvos anteriormente
  FIELDS.forEach(function (id) {
    if (args[id]) document.getElementById(id).value = args[id];
  });
  renderEditor('title');
  renderEditor('message');
  urlTipoInputs().forEach(function (r) { r.checked = r.value === (args.urlTipo || 'nenhum'); });
  toggleMidia();
  toggleUrl();
}

function onClickedNext() {
  activity.arguments = activity.arguments || {};
  activity.arguments.execute = activity.arguments.execute || {};

  var imagem = val('midiaTipo') === 'imagem';
  var semUrl = urlTipo() === 'nenhum';
  var inArguments = [{ contactKey: '{{Contact.Key}}' }];
  FIELDS.forEach(function (id) {
    var o = {};
    // só a mídia escolhida vai no payload; url só quando há tipo de URL
    var vazio = (id === 'icone' && imagem) || (id === 'imagemUrl' && !imagem) || (id === 'url' && semUrl);
    o[id] = vazio ? '' : val(id);
    inArguments.push(o);
  });
  inArguments.push({ urlTipo: urlTipo() });
  // Ocultos para o usuário
  inArguments.push({ journeyName: journeyName });
  inArguments.push({ journeyId: journeyId });
  Object.keys(FIXED).forEach(function (k) {
    var o = {};
    o[k] = FIXED[k];
    inArguments.push(o);
  });

  activity.arguments.execute.inArguments = inArguments;

  activity.metaData = activity.metaData || {};
  activity.metaData.isConfigured = true;

  connection.trigger('updateActivity', activity);
}
