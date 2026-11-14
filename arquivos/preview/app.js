const state = {
    page: 0,
    detail: null,
    running: true,
    selected: 'Meu mundo novo',
    consoleTab: 'logs',
    filter: 'all',
    overview: false,
    maxPlayers: 4,
    viewDistance: 8,
    messages: [{ name: 'Neescao', text: 'Alguém vai explorar comigo?' }],
    logs: [
        { time: '22:19:02', level: 'info', text: 'Servidor iniciado. Mundo “Meu mundo novo” carregado.' },
        { time: '22:19:05', level: 'warn', text: 'Acesso pela internet ainda não confirmado.' },
        { time: '22:19:14', level: 'info', text: 'Neescao entrou no servidor.' },
        { time: '22:21:02', level: 'info', text: 'Mundo salvo com sucesso.' }
    ]
};
const tabs = ['Início', 'Mundos', 'Console', 'Ajustes'];
const tabIcons = ['house', 'box', 'terminal', 'settings'];
const icon = name => `<i data-lucide="${name}"></i>`;
function escapeText(text) {
    return String(text).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
}
function row(name, label, value, action) {
    return `<button class="row" onclick="${action}">${icon(name)}<span class="label">${label}</span><span class="value">${value || ''}</span>${icon('chevron-right').replace('<i ', '<i class="chevron" ')}</button>`;
}
function heading(title, subtitle = '', brand = false) {
    return `<div class="heading">${brand ? '<span class="brand">MoboServer</span>' : ''}<h1>${title}</h1>${subtitle ? `<p class="subtitle">${subtitle}</p>` : ''}</div>`;
}
function home() {
    if (state.detail === 'players') return players();
    if (state.detail === 'internet') return internet();
    return heading('Servidor', '', true) + `
        <section class="card hero ${state.running ? '' : 'offline'}">
            <div class="status-label"><span class="status-dot"></span>${state.running ? 'Servidor online' : 'Servidor desligado'}</div>
            <h3>${state.selected}</h3>
            <p class="world-meta">Sobrevivência · Normal</p>
            <div class="stats">
                <div class="stat"><small>Tempo ativo</small><strong>${state.running ? '24 min' : '—'}</strong></div>
                <div class="stat"><small>Último salvamento</small><strong>${state.running ? 'Há 1 min' : '—'}</strong></div>
            </div>
            <button class="primary" onclick="toggleServer()">${icon(state.running ? 'square' : 'play')}${state.running ? 'Parar servidor' : 'Ligar servidor'}</button>
        </section>
        <h2>Conexão</h2>
        <section class="card endpoint">
            <div class="endpoint-head">${icon('globe')}Endereço de internet<span class="state-tag">Não verificado</span></div>
            <div class="address"><strong>45.160.85.157</strong><button aria-label="Ver acesso pela internet" onclick="openDetail('internet')">${icon('arrow-up-right')}</button></div>
            <p>A conexão de fora da sua rede ainda precisa ser confirmada.</p>
        </section>
        <section class="card list">
            ${row('wifi', 'Rede local', '192.168.1.106', "show('Rede local', 'Endereço para quem está no mesmo Wi-Fi ou na mesma rede cabeada. Porta 19132. Dados de demonstração.')")}
            ${row('users', 'Jogadores online', state.running ? '1 de 4' : '0 de 4', "openDetail('players')")}
            ${row('circle-help', 'Como entrar', '', "show('Entrar no servidor', 'No Minecraft Bedrock, adicione o endereço e a porta do servidor. O acesso externo precisa estar confirmado antes de compartilhar um convite de internet.')")}
        </section>`;
}
function worlds() {
    return heading('Mundos', 'Seu mundo, sempre por perto.') + `
        <div class="world-actions"><button class="primary" onclick="show('Importar mundo', 'No aplicativo, este botão abre o seletor de arquivos .mcworld. A importação preserva uma cópia do original.')">${icon('upload')}Importar mundo</button></div>
        ${['Meu mundo novo', 'TesteServer'].map((name, index) => `
            <section class="card">
                <div class="world-preview">${icon('image')}Sem imagem do mundo</div>
                <div class="world-info">
                    <div class="world-top"><h3>${name}</h3><button aria-label="Opções de ${name}" onclick="show('Opções do mundo', 'Exportar cópia, validar arquivos e excluir com confirmação. Essas ações serão conectadas ao aplicativo.')">${icon('ellipsis')}</button></div>
                    <p>${index ? '147 KB' : '45 KB'} · Importado hoje</p>
                    <button class="select-world" onclick="selectWorld('${name}')">${state.selected === name ? (state.running ? 'Em execução' : 'Selecionado') : 'Usar este mundo'}</button>
                </div>
            </section>`).join('')}`;
}
function consoleScreen() {
    const entries = state.logs.filter(entry => state.filter === 'all' || entry.level === state.filter);
    return heading('Console', 'Acompanhe a sessão.') + `
        <div class="segment" role="tablist" aria-label="Console">
            <button role="tab" aria-selected="${state.consoleTab === 'logs'}" class="${state.consoleTab === 'logs' ? 'active' : ''}" onclick="setConsoleTab('logs')">Registros</button>
            <button role="tab" aria-selected="${state.consoleTab === 'chat'}" class="${state.consoleTab === 'chat' ? 'active' : ''}" onclick="setConsoleTab('chat')">Chat</button>
        </div>
        ${state.consoleTab === 'logs' ? `
            <div class="console-actions">
                <select aria-label="Filtrar registros" onchange="state.filter=this.value;render()">
                    <option value="all" ${state.filter === 'all' ? 'selected' : ''}>Todos os registros</option>
                    <option value="warn" ${state.filter === 'warn' ? 'selected' : ''}>Avisos</option>
                    <option value="info" ${state.filter === 'info' ? 'selected' : ''}>Informações</option>
                </select>
                <button aria-label="Copiar registros" onclick="copyLogs()">${icon('copy')}</button>
                <button aria-label="Limpar registros" onclick="state.logs=[];render()">${icon('trash-2')}</button>
            </div>
            <div class="card log-list">${entries.length ? entries.map(entry => `<article class="log-item"><div class="log-meta"><time>${entry.time}</time><span class="log-level ${entry.level}">${entry.level === 'warn' ? 'Aviso' : 'Info'}</span></div><p>${escapeText(entry.text)}</p></article>`).join('') : '<p class="hint">Nenhum registro neste filtro.</p>'}</div>
        ` : `
            <div class="card chat-messages">${state.messages.map(message => `<div class="chat-bubble"><small>${escapeText(message.name)}</small>${escapeText(message.text)}</div>`).join('')}</div>
            <form class="chat-input" onsubmit="sendMessage(event)"><input aria-label="Mensagem para os jogadores" placeholder="Mensagem para os jogadores" maxlength="250" required ${state.running ? '' : 'disabled'}><button aria-label="Enviar mensagem" ${state.running ? '' : 'disabled'}>${icon('send')}</button></form>
            <p class="hint">${state.running ? 'Mensagens demonstrativas nesta prévia.' : 'Ligue o servidor para enviar mensagens.'}</p>
        `}`;
}
function settings() {
    if (state.detail === 'advanced') return advanced();
    return heading('Ajustes', 'Tudo no seu ritmo.') + `
        <p class="eyebrow">SERVIDOR</p>
        <div class="card list">
            ${row('server', 'Nome', 'MoboServer', "show('Nome do servidor', 'A integração deve editar apenas o nome, preservando o mundo selecionado e os demais ajustes.')")}
            ${row('ethernet-port', 'Porta', '19132', "show('Porta do servidor', 'Aplicada na próxima inicialização. Validar de 1024 a 65535.')")}
        </div>
        <p class="eyebrow">DESEMPENHO</p>
        <div class="card list">
            <div class="row">${icon('users')}<span class="label">Limite de jogadores</span><span class="value" id="maxValue">${state.maxPlayers}</span></div>
            <input class="range" aria-label="Limite de jogadores" type="range" min="1" max="5" value="${state.maxPlayers}" oninput="state.maxPlayers=+this.value;document.getElementById('maxValue').textContent=this.value">
            <div class="row">${icon('eye')}<span class="label">Distância de visão</span><span class="value" id="viewValue">${state.viewDistance} chunks</span></div>
            <input class="range" aria-label="Distância de visão" type="range" min="4" max="16" value="${state.viewDistance}" oninput="state.viewDistance=+this.value;document.getElementById('viewValue').textContent=this.value+' chunks'">
        </div>
        <p class="hint">Distâncias menores reduzem o uso de memória.</p>
        <p class="eyebrow">PREFERÊNCIAS</p>
        <div class="card list">
            ${row('gamepad-2', 'Jogo', 'Sobrevivência', "show('Jogo', 'Modo de jogo e dificuldade usam as opções suportadas pelo servidor.')")}
            ${row('moon', 'Aparência', document.body.classList.contains('dark') ? 'Escuro' : 'Claro', 'toggleTheme()')}
            ${row('sliders-horizontal', 'Avançado', '', "openDetail('advanced')")}
        </div>`;
}
function backButton() {
    return `<button class="back" onclick="history.back()">${icon('chevron-left')}Voltar</button>`;
}
function players() {
    return backButton() + heading('Jogadores', state.running ? '1 de 4 conectados' : 'Ninguém conectado') + (state.running ? `
        <section class="card list"><div class="player-row"><button class="avatar" aria-label="Motivo da prévia indisponível" onclick="show('Skin do jogador', 'Neste exemplo, a prévia da skin ainda está carregando. No app, o motivo real precisa ser exibido quando o formato não puder ser renderizado.')">${icon('user-round')}</button><span class="player-name">Neescao<small>Prévia da skin pendente</small></span><span class="ping">${icon('signal')}2 ms</span></div></section>
        <section class="card player-summary"><div class="stat"><small>Latência mediana</small><strong>2 ms</strong></div><div class="stat"><small>Jogadores medidos</small><strong>1</strong></div></section>
        <p class="hint">A latência é medida entre cada jogador e o celular.</p>` : '<div class="card endpoint"><p>Os jogadores aparecerão aqui quando entrarem.</p></div>');
}
function internet() {
    return backButton() + heading('Internet', 'Conecte seus amigos de outras redes.') + `
        <div class="info-callout"><strong>Acesso externo não confirmado</strong>Detectamos um endereço público, mas ainda precisamos testar uma conexão externa.</div>
        <div class="card list">${row('globe', 'IP público observado', '45.160.85.157', "show('IP público observado', 'Endereço visto por um servidor externo. Isso, sozinho, não confirma que o Minecraft pode receber conexões.')")}${row('router', 'WAN do roteador', '100.64.2.77', "show('Endereço WAN do roteador', 'Este endereço está na faixa de CGNAT. A regra UPnP do roteador não atravessa o NAT da operadora.')")}</div>
        <button class="primary" onclick="show('Verificação de acesso', 'Esta prévia não realiza testes de rede. No app, conectar a uma verificação externa real e compatível com o transporte do Minecraft.')">${icon('activity')}Verificar acesso</button>
        <p class="hint">Quando houver um túnel funcional, seu endereço de conexão aparecerá na tela inicial.</p>`;
}
function advanced() {
    return backButton() + heading('Avançado', 'Conexão e diagnóstico.') + `<div class="card list">${row('shield-check', 'Contas verificadas', 'Ativo', "show('Autenticação','Utilizar a verificação real das contas Microsoft.')")}${row('network', 'Acesso externo', 'Configurar', "show('Acesso externo','Preservar o fluxo de relay existente e indicar compatibilidade real com o transporte em uso.')")}${row('activity', 'Diagnóstico', '', "show('Diagnóstico','Exibir estado dos transportes, falhas e versão do aplicativo.')")}</div>`;
}
function render() {
    const pages = [home(), worlds(), consoleScreen(), settings()];
    document.getElementById('board').innerHTML = pages.map((content, index) => `
        <section class="phone ${state.overview || state.page === index ? '' : 'hidden'}">
            <div class="system"><span>9:41</span><span>DEMONSTRAÇÃO</span></div>
            <div class="content">${content}</div>
            <nav class="nav" aria-label="Navegação principal">${tabs.map((label, tab) => `<button aria-current="${index === tab ? 'page' : 'false'}" class="${index === tab ? 'active' : ''}" onclick="goPage(${tab})">${icon(tabIcons[tab])}${label}</button>`).join('')}</nav>
            <div class="gesture"></div>
        </section>`).join('');
    document.body.classList.toggle('overview', state.overview);
    document.getElementById('overview').textContent = state.overview ? 'Ver um celular' : 'Ver todas as telas';
    document.getElementById('theme').textContent = document.body.classList.contains('dark') ? 'Modo claro' : 'Modo escuro';
    lucide.createIcons();
}
function goPage(page) { state.page = page; state.detail = null; history.replaceState({ page, detail: null }, ''); render(); }
function openDetail(detail) { state.detail = detail; history.pushState({ page: state.page, detail }, ''); render(); }
window.addEventListener('popstate', event => { state.page = event.state?.page ?? 0; state.detail = event.state?.detail ?? null; render(); });
history.replaceState({ page: 0, detail: null }, '');
function toggleServer() { state.running = !state.running; render(); }
function selectWorld(name) { if (state.running) { show('Servidor ligado', 'Pare o servidor para trocar de mundo.'); return; } state.selected = name; render(); }
function setConsoleTab(tab) { state.consoleTab = tab; render(); }
function toggleTheme() { document.body.classList.toggle('dark'); render(); }
function show(title, text) { document.getElementById('detail').innerHTML = `<h2>${escapeText(title)}</h2><p>${escapeText(text)}</p>`; document.getElementById('dialog').showModal(); }
function sendMessage(event) { event.preventDefault(); const input = event.target.querySelector('input'); if (!state.running || !input.value.trim()) return; state.messages.push({ name: 'Você · prévia', text: input.value.trim() }); render(); }
async function copyLogs() { const text = state.logs.map(entry => `${entry.time} [${entry.level}] ${entry.text}`).join('\n'); try { await navigator.clipboard.writeText(text); show('Registros copiados', 'O texto está disponível para colar.'); } catch { show('Registros', text || 'Nenhum registro.'); } }
document.getElementById('theme').onclick = toggleTheme;
document.getElementById('overview').onclick = () => { state.overview = !state.overview; state.detail = null; render(); };
render();
