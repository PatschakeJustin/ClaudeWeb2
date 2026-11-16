'use strict';
// Discord bot for the Humantize Hub: status as presence, slash commands, notifications from the app.
// Only Discord users listed in discord.allowedUsers may run commands.

const os = require('node:os');
const { execFile, spawn } = require('node:child_process');

function run(cmd, args, opts = {}) {
  return new Promise((resolve) => {
    execFile(cmd, args, { timeout: opts.timeout || 20000, maxBuffer: 4 * 1024 * 1024, ...opts }, (err, stdout, stderr) => {
      resolve({ code: err ? (err.code ?? 1) : 0, out: String(stdout || ''), err: String(stderr || '') });
    });
  });
}

/** Runs Claude Code headless with the prompt on stdin; resolves with its text answer. */
function runClaude(prompt, cwd, timeoutMs) {
  return new Promise((resolve) => {
    const env = { ...process.env, PATH: `${os.homedir()}/.local/bin:${process.env.PATH}` };
    const child = spawn('claude', ['-p', '--permission-mode', 'acceptEdits'], { cwd, env });
    let out = '', err = '';
    const timer = setTimeout(() => { child.kill('SIGINT'); err += '\n[Zeitlimit erreicht]'; }, timeoutMs);
    child.stdout.on('data', (d) => { out += d; });
    child.stderr.on('data', (d) => { err += d; });
    child.on('error', (e) => { clearTimeout(timer); resolve({ code: 127, out: '', err: e.message }); });
    child.on('close', (code) => { clearTimeout(timer); resolve({ code, out, err }); });
    child.stdin.end(prompt);
  });
}

function bytes(n) { const g = n / 1024 ** 3; return g >= 1 ? `${g.toFixed(1)} GB` : `${(n / 1024 ** 2).toFixed(0)} MB`; }
function clip(s, n = 1900) { s = String(s); return s.length > n ? s.slice(0, n - 20) + '\n… (gekürzt)' : s; }

async function statusText(cfg) {
  const load = os.loadavg().map((x) => x.toFixed(2)).join(' / ');
  const mem = 1 - os.freemem() / os.totalmem();
  const up = os.uptime();
  const disk = await run('df', ['-h', '/']);
  const diskLine = disk.out.trim().split('\n')[1] || '';
  const lines = [
    `**${os.hostname()}** – online`,
    `Laufzeit: ${Math.floor(up / 86400)} T ${Math.floor((up % 86400) / 3600)} Std`,
    `Last (1/5/15 min): ${load}  ·  CPUs: ${os.cpus().length}`,
    `RAM: ${(mem * 100).toFixed(0)} % von ${bytes(os.totalmem())}`,
    `Festplatte /: ${diskLine.split(/\s+/).slice(1, 5).join('  ')}`,
  ];
  for (const s of (cfg.discord.services || [])) {
    const r = await run('systemctl', ['is-active', s]);
    lines.push(`Dienst ${s}: ${r.out.trim() || 'unbekannt'}`);
  }
  return lines.join('\n');
}

function startBot(cfg) {
  const { Client, GatewayIntentBits, ActivityType, SlashCommandBuilder, Events, AttachmentBuilder } = require('discord.js');
  const d = cfg.discord;
  const allowed = new Set((d.allowedUsers || []).map(String));
  const services = d.services || [];
  let ready = false;

  const commands = [
    new SlashCommandBuilder().setName('status').setDescription('Zustand des Servers anzeigen'),
    new SlashCommandBuilder().setName('claude').setDescription('Claude Code auf dem Server eine Aufgabe geben')
      .addStringOption((o) => o.setName('aufgabe').setDescription('Was soll Claude tun?').setRequired(true))
      .addStringOption((o) => o.setName('ordner').setDescription('Arbeitsordner (optional)')),
  ];
  if (services.length) {
    commands.push(new SlashCommandBuilder().setName('dienst').setDescription('Einen Dienst steuern')
      .addStringOption((o) => o.setName('name').setDescription('Dienst').setRequired(true)
        .addChoices(...services.slice(0, 25).map((s) => ({ name: s, value: s }))))
      .addStringOption((o) => o.setName('aktion').setDescription('Aktion').setRequired(true)
        .addChoices({ name: 'Status', value: 'status' }, { name: 'Neustart', value: 'restart' },
          { name: 'Starten', value: 'start' }, { name: 'Stoppen', value: 'stop' })));
  }
  if (d.allowShell) {
    commands.push(new SlashCommandBuilder().setName('befehl').setDescription('Shell-Befehl auf dem Server ausführen')
      .addStringOption((o) => o.setName('befehl').setDescription('Befehl').setRequired(true)));
  }

  const client = new Client({ intents: [GatewayIntentBits.Guilds] });

  async function updatePresence() {
    if (!client.user) return;
    const mem = Math.round((1 - os.freemem() / os.totalmem()) * 100);
    client.user.setPresence({
      status: 'online',
      activities: [{ name: `Last ${os.loadavg()[0].toFixed(2)} · RAM ${mem} %`, type: ActivityType.Watching }],
    });
  }

  client.once(Events.ClientReady, async () => {
    ready = true;
    console.log(`Discord-Bot angemeldet als ${client.user.tag}`);
    // Guild commands are available immediately (global ones can take up to an hour).
    for (const guild of client.guilds.cache.values()) {
      try { await guild.commands.set(commands.map((c) => c.toJSON())); } catch (e) { console.error('Befehle registrieren:', e.message); }
    }
    updatePresence();
    setInterval(updatePresence, 60_000);
  });

  client.on(Events.GuildCreate, (guild) => guild.commands.set(commands.map((c) => c.toJSON())).catch(() => {}));

  client.on(Events.InteractionCreate, async (i) => {
    if (!i.isChatInputCommand()) return;
    if (!allowed.has(i.user.id)) {
      return i.reply({ content: 'Keine Berechtigung. Deine Discord-ID muss in der Hub-Konfiguration freigegeben sein.', ephemeral: true });
    }
    try {
      if (i.commandName === 'status') {
        await i.deferReply();
        await i.editReply(clip(await statusText(cfg)));
      } else if (i.commandName === 'claude') {
        const task = i.options.getString('aufgabe', true);
        const dir = i.options.getString('ordner') || d.claudeWorkDir || os.homedir();
        await i.deferReply();
        await i.editReply(`Claude arbeitet in \`${dir}\` …`);
        const r = await runClaude(task, dir, (d.claudeTimeoutMinutes || 15) * 60_000);
        const text = (r.out || r.err || '(keine Ausgabe)').trim();
        if (text.length > 1900) {
          await i.editReply({ content: clip(text, 1500), files: [new AttachmentBuilder(Buffer.from(text), { name: 'antwort.md' })] });
        } else await i.editReply(text);
      } else if (i.commandName === 'dienst') {
        const name = i.options.getString('name', true);
        const action = i.options.getString('aktion', true);
        if (!services.includes(name)) return i.reply({ content: 'Dienst nicht freigegeben.', ephemeral: true });
        await i.deferReply();
        const r = action === 'status'
          ? await run('systemctl', ['status', '--no-pager', '-n', '5', name])
          : await run('systemctl', [action, name]);
        const after = await run('systemctl', ['is-active', name]);
        await i.editReply(clip(`\`systemctl ${action} ${name}\` → ${r.code === 0 ? 'OK' : 'Fehler ' + r.code}, jetzt: **${after.out.trim()}**\n` +
          '```\n' + (r.out || r.err).trim().slice(0, 1500) + '\n```'));
      } else if (i.commandName === 'befehl' && d.allowShell) {
        const cmd = i.options.getString('befehl', true);
        await i.deferReply();
        const r = await run('bash', ['-lc', cmd], { timeout: 120000 });
        await i.editReply(clip(`Exit ${r.code}\n\`\`\`\n${(r.out + r.err).trim() || '(keine Ausgabe)'}\n\`\`\``));
      }
    } catch (e) {
      const msg = 'Fehler: ' + e.message;
      if (i.deferred || i.replied) await i.editReply(msg).catch(() => {}); else await i.reply({ content: msg, ephemeral: true }).catch(() => {});
    }
  });

  client.login(d.token).catch((e) => console.error('Discord-Anmeldung fehlgeschlagen:', e.message));

  return {
    ready: () => ready,
    notify: async (text, level) => {
      if (!ready || !d.channelId) return false;
      const icon = { error: '🔴', warning: '🟠', success: '🟢' }[level] || 'ℹ️';
      const channel = await client.channels.fetch(d.channelId).catch(() => null);
      if (!channel || !channel.isTextBased()) return false;
      await channel.send(clip(`${icon} ${text}`));
      return true;
    },
  };
}

module.exports = { startBot, statusText };
