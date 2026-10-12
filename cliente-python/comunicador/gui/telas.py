"""Telas Tkinter do Cliente_Terceiro: login, cadastro, sala e seletores.

Este modulo so desenha e repassa acoes. A logica testavel (validacao, texto
alternativo, ordem e duplicatas, estados) esta em ``modelo``; a orquestracao
(rede, historico, erros) esta em ``app``.

- Sala: ``tk.Text`` somente leitura; ``image_create`` insere avatar e
  figurinha; ``tag_configure(foreground=cor)`` colore o apelido.
  Os ``PhotoImage`` ficam no ``CacheImagens``.
- Seletor_de_Emojis: ``Toplevel`` com os botoes posicionados por
  ``grid(row, column)``, 6 linhas por 8 colunas.
- Seletor de figurinhas: ``Toplevel`` com miniaturas em ``grid``.
- Quando uma imagem nao carrega, ou no modo texto, aparece a representacao
  textual alternativa: ``[avatar: av_03]``, ``[figurinha: fig_03]``,
  ``(cor #1E88E5)``, ``[emoji: U+1F600]``.
"""
from __future__ import annotations

import tkinter as tk
from dataclasses import dataclass
from tkinter import colorchooser, ttk
from typing import Any, Callable, Mapping, Optional, Sequence

from comunicador.gui.imagens import LADO_MINIATURA, CacheImagens
from comunicador.gui.modelo import (
    COR_PADRAO,
    ApresentacaoEstado,
    ListaMensagens,
    LinhaMensagem,
    ResultadoExibicao,
    codigo_emoji,
    descrever_mensagem,
    eh_cor_valida,
    indice_tk_para_posicao,
    limitar,
    posicao_para_indice_tk,
    texto_avatar,
    texto_figurinha,
)

COLUNAS_EMOJIS = 8
LINHAS_EMOJIS = 6
COLUNAS_FIGURINHAS = 4
COLUNAS_AVATARES = 6
COR_ERRO = "#B00020"
COR_INFO = "#2E7D32"
COR_SECUNDARIA = "#666666"


# ---------------------------------------------------------------------------
# Auxiliares
# ---------------------------------------------------------------------------

def _campo_com_rotulo(mestre: tk.Misc, linha: int, rotulo: str, **opcoes: Any) -> ttk.Entry:
    ttk.Label(mestre, text=rotulo).grid(row=linha, column=0, sticky="e", padx=(0, 8), pady=4)
    entrada = ttk.Entry(mestre, width=28, **opcoes)
    entrada.grid(row=linha, column=1, sticky="we", pady=4)
    return entrada


def _definir_rotulo(rotulo: ttk.Label, texto: str, cor: str) -> None:
    rotulo.configure(text=texto, foreground=cor)


class CampoEntry:
    """Adaptador de ``ttk.Entry`` para o ``CampoDeTexto`` do ``ControladorEnvio``."""

    def __init__(self, entrada: ttk.Entry) -> None:
        self._entrada = entrada

    def ler(self) -> str:
        return self._entrada.get()

    def escrever(self, texto: str) -> None:
        self._entrada.delete(0, tk.END)
        self._entrada.insert(0, texto)


def detectar_astral_duplo(entrada: ttk.Entry) -> bool:
    """``True`` se esta versao do Tk conta um caractere fora do plano basico
    (por exemplo um emoji) como 2 indices. Mede inserindo e removendo um emoji."""
    try:
        entrada.insert(tk.END, "\U0001F600")
        tamanho = entrada.index(tk.END)
        entrada.delete(0, tk.END)
        return tamanho == 2
    except tk.TclError:
        return False


# ---------------------------------------------------------------------------
# Login
# ---------------------------------------------------------------------------

class TelaLogin(ttk.Frame):
    """Formulario de login e senha."""

    def __init__(
        self,
        mestre: tk.Misc,
        ao_entrar: Callable[[str, str], None],
        ao_cadastrar: Callable[[], None],
    ) -> None:
        super().__init__(mestre, padding=24)
        self._ao_entrar = ao_entrar
        self.columnconfigure(0, weight=1)
        self.rowconfigure(0, weight=1)
        centro = ttk.Frame(self)
        centro.grid(row=0, column=0)
        ttk.Label(centro, text="Comunicador Distribuído", font=("TkDefaultFont", 16, "bold")).grid(
            row=0, column=0, columnspan=2, pady=(0, 16)
        )
        self.campo_login = _campo_com_rotulo(centro, 1, "Login")
        self.campo_senha = _campo_com_rotulo(centro, 2, "Senha", show="•")
        self.mensagem = ttk.Label(centro, text="", wraplength=320, justify="left")
        self.mensagem.grid(row=3, column=0, columnspan=2, sticky="we", pady=(8, 0))
        botoes = ttk.Frame(centro)
        botoes.grid(row=4, column=0, columnspan=2, pady=(12, 0))
        self.botao_entrar = ttk.Button(botoes, text="Entrar", command=self._enviar)
        self.botao_entrar.grid(row=0, column=0, padx=4)
        self.botao_cadastrar = ttk.Button(botoes, text="Criar conta", command=ao_cadastrar)
        self.botao_cadastrar.grid(row=0, column=1, padx=4)
        for campo in (self.campo_login, self.campo_senha):
            campo.bind("<Return>", lambda _e: self._enviar())
            campo.bind("<KP_Enter>", lambda _e: self._enviar())

    def _enviar(self) -> None:
        if str(self.botao_entrar.cget("state")) == "disabled":
            return
        self._ao_entrar(self.campo_login.get(), self.campo_senha.get())

    def mostrar_erro(self, texto: str) -> None:
        _definir_rotulo(self.mensagem, texto, COR_ERRO)

    def mostrar_info(self, texto: str) -> None:
        _definir_rotulo(self.mensagem, texto, COR_INFO)

    def definir_ocupado(self, ocupado: bool) -> None:
        estado = "disabled" if ocupado else "normal"
        self.botao_entrar.configure(state=estado)
        self.botao_cadastrar.configure(state=estado)

    def definir_login(self, login: str) -> None:
        self.campo_login.delete(0, tk.END)
        self.campo_login.insert(0, login)

    def limpar_senha(self) -> None:
        self.campo_senha.delete(0, tk.END)

    def focar(self) -> None:
        (self.campo_senha if self.campo_login.get() else self.campo_login).focus_set()


# ---------------------------------------------------------------------------
# Cadastro
# ---------------------------------------------------------------------------

class TelaCadastro(ttk.Frame):
    """Formulario de cadastro: login, apelido, senha, avatar e cor."""

    def __init__(
        self,
        mestre: tk.Misc,
        imagens: CacheImagens,
        avatares: Sequence[str],
        ao_criar: Callable[[Mapping[str, str]], None],
        ao_voltar: Callable[[], None],
    ) -> None:
        super().__init__(mestre, padding=24)
        self._ao_criar = ao_criar
        self.columnconfigure(0, weight=1)
        centro = ttk.Frame(self)
        centro.grid(row=0, column=0)
        ttk.Label(centro, text="Criar conta", font=("TkDefaultFont", 16, "bold")).grid(
            row=0, column=0, columnspan=2, pady=(0, 12)
        )
        self.campo_login = _campo_com_rotulo(centro, 1, "Login")
        self.campo_apelido = _campo_com_rotulo(centro, 2, "Apelido")
        self.campo_senha = _campo_com_rotulo(centro, 3, "Senha", show="•")

        ttk.Label(centro, text="Avatar").grid(row=4, column=0, sticky="ne", padx=(0, 8), pady=4)
        grade_avatares = ttk.Frame(centro)
        grade_avatares.grid(row=4, column=1, sticky="w", pady=4)
        self.avatar = tk.StringVar(master=self, value=avatares[0] if avatares else "")
        self._miniaturas: list[Any] = []  # referencias aos PhotoImage
        for i, id_ in enumerate(avatares):
            imagem = imagens.avatar(id_, LADO_MINIATURA)
            opcoes: dict[str, Any] = {"image": imagem} if imagem is not None else {"text": texto_avatar(id_)}
            if imagem is not None:
                self._miniaturas.append(imagem)
            ttk.Radiobutton(
                grade_avatares, variable=self.avatar, value=id_, style="Toolbutton", **opcoes
            ).grid(row=i // COLUNAS_AVATARES, column=i % COLUNAS_AVATARES, padx=1, pady=1)

        ttk.Label(centro, text="Cor").grid(row=5, column=0, sticky="e", padx=(0, 8), pady=4)
        linha_cor = ttk.Frame(centro)
        linha_cor.grid(row=5, column=1, sticky="w", pady=4)
        self.cor = tk.StringVar(master=self, value=COR_PADRAO)
        ttk.Entry(linha_cor, textvariable=self.cor, width=10).grid(row=0, column=0)
        self._amostra = tk.Label(linha_cor, width=3, background=COR_PADRAO, relief="sunken")
        self._amostra.grid(row=0, column=1, padx=6)
        ttk.Button(linha_cor, text="Escolher…", command=self._escolher_cor).grid(row=0, column=2)
        self.cor.trace_add("write", lambda *_: self._atualizar_amostra())

        self.mensagem = ttk.Label(centro, text="", wraplength=360, justify="left")
        self.mensagem.grid(row=6, column=0, columnspan=2, sticky="we", pady=(8, 0))
        botoes = ttk.Frame(centro)
        botoes.grid(row=7, column=0, columnspan=2, pady=(12, 0))
        self.botao_criar = ttk.Button(botoes, text="Criar conta", command=self._enviar)
        self.botao_criar.grid(row=0, column=0, padx=4)
        self.botao_voltar = ttk.Button(botoes, text="Voltar", command=ao_voltar)
        self.botao_voltar.grid(row=0, column=1, padx=4)
        for campo in (self.campo_login, self.campo_apelido, self.campo_senha):
            campo.bind("<Return>", lambda _e: self._enviar())

    def _atualizar_amostra(self) -> None:
        cor = self.cor.get().strip()
        if eh_cor_valida(cor):
            self._amostra.configure(background=cor)

    def _escolher_cor(self) -> None:
        atual = self.cor.get().strip()
        _rgb, escolhida = colorchooser.askcolor(
            color=atual if eh_cor_valida(atual) else COR_PADRAO, parent=self, title="Cor do apelido"
        )
        if escolhida:
            self.cor.set(str(escolhida).upper())

    def ler_campos(self) -> dict[str, str]:
        return {
            "login": self.campo_login.get().strip().lower(),
            "apelido": self.campo_apelido.get().strip(),
            "senha": self.campo_senha.get(),
            "avatar": self.avatar.get(),
            "cor": self.cor.get().strip().upper(),
        }

    def _enviar(self) -> None:
        if str(self.botao_criar.cget("state")) == "disabled":
            return
        self._ao_criar(self.ler_campos())

    def mostrar_erro(self, texto: str) -> None:
        _definir_rotulo(self.mensagem, texto, COR_ERRO)

    def definir_ocupado(self, ocupado: bool) -> None:
        self.botao_criar.configure(state="disabled" if ocupado else "normal")

    def limpar_senha(self) -> None:
        self.campo_senha.delete(0, tk.END)

    def limpar(self) -> None:
        for campo in (self.campo_login, self.campo_apelido, self.campo_senha):
            campo.delete(0, tk.END)
        self.mostrar_erro("")


# ---------------------------------------------------------------------------
# Sala
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class AcoesSala:
    """Acoes que a sala dispara (tratadas por ``app.Aplicacao``)."""

    enviar: Callable[[], None]
    tecla: Callable[[str], None]
    emoji: Callable[[str], None]
    figurinha: Callable[[str], None]
    sair: Callable[[], None]
    tentar_novamente: Callable[[], None]


class TelaSala(ttk.Frame):
    """Sala de bate-papo: mensagens, campo de texto, Enviar, emojis e figurinhas."""

    def __init__(
        self,
        mestre: tk.Misc,
        imagens: CacheImagens,
        acoes: AcoesSala,
        *,
        emojis: Sequence[str],
        emojis_conhecidos: Sequence[str],
        figurinhas: Sequence[str],
    ) -> None:
        super().__init__(mestre, padding=8)
        self._imagens = imagens
        self._acoes = acoes
        self._emojis = tuple(emojis)
        self._emojis_conhecidos = tuple(emojis_conhecidos)
        self._figurinhas = tuple(figurinhas)
        self._tags_cor: set[str] = set()
        self._seletor_emojis: Optional[tk.Toplevel] = None
        self._seletor_figurinhas: Optional[tk.Toplevel] = None
        self.columnconfigure(0, weight=1)
        self.rowconfigure(1, weight=1)

        topo = ttk.Frame(self)
        topo.grid(row=0, column=0, sticky="we", pady=(0, 6))
        topo.columnconfigure(0, weight=1)
        self.rotulo_perfil = ttk.Label(topo, text="", font=("TkDefaultFont", 11, "bold"))
        self.rotulo_perfil.grid(row=0, column=0, sticky="w")
        self.rotulo_estado = ttk.Label(topo, text="")
        self.rotulo_estado.grid(row=0, column=1, padx=8)
        self.botao_tentar = ttk.Button(topo, text="Tentar novamente", command=acoes.tentar_novamente)
        self.botao_tentar.grid(row=0, column=2, padx=4)
        self.botao_tentar.grid_remove()
        ttk.Button(topo, text="Sair", command=acoes.sair).grid(row=0, column=3)

        corpo = ttk.Frame(self)
        corpo.grid(row=1, column=0, sticky="nsew")
        corpo.columnconfigure(0, weight=1)
        corpo.rowconfigure(0, weight=1)
        self.texto = tk.Text(corpo, wrap="word", state="disabled", height=18, padx=6, pady=6, spacing1=1)
        barra = ttk.Scrollbar(corpo, orient="vertical", command=self.texto.yview)
        self.texto.configure(yscrollcommand=barra.set)
        self.texto.grid(row=0, column=0, sticky="nsew")
        barra.grid(row=0, column=1, sticky="ns")
        self.texto.tag_configure("apelido", font=("TkDefaultFont", 11, "bold"))
        self.texto.tag_configure("hora", foreground=COR_SECUNDARIA, font=("TkDefaultFont", 9))
        self.texto.tag_configure("corpo", lmargin1=8, lmargin2=8)
        self.texto.tag_configure("alternativo", foreground=COR_SECUNDARIA)
        self.texto.tag_configure("ilegivel", foreground=COR_SECUNDARIA, font=("TkDefaultFont", 10, "italic"))

        self.aviso = ttk.Label(self, text="", wraplength=560, justify="left")
        self.aviso.grid(row=2, column=0, sticky="we", pady=(4, 0))

        rodape = ttk.Frame(self)
        rodape.grid(row=3, column=0, sticky="we", pady=(6, 0))
        rodape.columnconfigure(2, weight=1)
        ttk.Button(rodape, text="Emojis", command=self.alternar_seletor_emojis).grid(row=0, column=0, padx=(0, 4))
        ttk.Button(rodape, text="Figurinhas", command=self.alternar_seletor_figurinhas).grid(
            row=0, column=1, padx=(0, 6)
        )
        self.entrada = ttk.Entry(rodape)
        self.entrada.grid(row=0, column=2, sticky="we")
        self.botao_enviar = ttk.Button(rodape, text="Enviar", command=acoes.enviar)
        self.botao_enviar.grid(row=0, column=3, padx=(6, 0))
        for tecla in ("<Return>", "<KP_Enter>"):
            self.entrada.bind(tecla, lambda e: acoes.tecla(e.keysym))
        self.campo = CampoEntry(self.entrada)
        self._astral_duplo = detectar_astral_duplo(self.entrada)

    # ----- cabecalho e estado -------------------------------------------------

    def definir_perfil(self, perfil: Optional[Mapping[str, Any]]) -> None:
        if not perfil:
            self.rotulo_perfil.configure(text="")
            return
        apelido = limitar(perfil.get("apelido"), 30)
        login = limitar(perfil.get("login"), 20)
        self.rotulo_perfil.configure(text=f"{apelido} ({login})" if apelido else login)

    def definir_estado(self, ap: ApresentacaoEstado) -> None:
        self.rotulo_estado.configure(text=ap.texto, foreground=COR_ERRO if ap.desconectado else COR_SECUNDARIA)
        self.botao_enviar.configure(state="normal" if ap.enviar_habilitado else "disabled")
        if ap.permitir_tentar_novamente:
            self.botao_tentar.grid()
        else:
            self.botao_tentar.grid_remove()

    def mostrar_aviso(self, texto: str, erro: bool = True) -> None:
        _definir_rotulo(self.aviso, texto, COR_ERRO if erro else COR_INFO)

    # ----- campo de texto -----------------------------------------------------

    def focar_campo(self) -> None:
        self.entrada.focus_set()

    def posicao_cursor(self) -> int:
        """Posicao do cursor do campo em pontos de codigo."""
        return indice_tk_para_posicao(self.entrada.get(), self.entrada.index(tk.INSERT), self._astral_duplo)

    def definir_cursor(self, posicao: int) -> None:
        self.entrada.icursor(posicao_para_indice_tk(self.entrada.get(), posicao, self._astral_duplo))

    # ----- mensagens ----------------------------------------------------------

    def limpar(self) -> None:
        self.fechar_seletores()
        self._apagar_mensagens()
        self.entrada.delete(0, tk.END)
        self.mostrar_aviso("")
        self.definir_perfil(None)

    def _apagar_mensagens(self) -> None:
        self.texto.configure(state="normal")
        self.texto.delete("1.0", tk.END)
        self.texto.configure(state="disabled")

    def exibir(self, resultado: ResultadoExibicao, lista: ListaMensagens) -> None:
        """Mostra as mensagens novas; redesenha tudo se alguma caiu antes do fim."""
        perto_do_fim = self.texto.yview()[1] >= 0.98
        if resultado.redesenhar:
            self._apagar_mensagens()
            mensagens = lista.itens
        else:
            mensagens = list(resultado.novas)
        self.texto.configure(state="normal")
        try:
            for m in mensagens:
                self._desenhar(descrever_mensagem(m, emojis=self._emojis_conhecidos))
        finally:
            self.texto.configure(state="disabled")
        if perto_do_fim or resultado.redesenhar:
            self.texto.see(tk.END)

    def _tag_da_cor(self, cor: str) -> str:
        nome = f"cor_{cor}"
        if nome not in self._tags_cor:
            self.texto.tag_configure(nome, foreground=cor, font=("TkDefaultFont", 11, "bold"))
            self._tags_cor.add(nome)
        return nome

    def _inserir_imagem(self, imagem: Any, alternativa: str) -> None:
        """Insere ``imagem``; se ela for ``None`` ou o Tk recusar, insere ``alternativa``."""
        if imagem is not None:
            try:
                self.texto.image_create(tk.END, image=imagem, padx=4)
                return
            except tk.TclError:
                pass
        self.texto.insert(tk.END, alternativa + " ", "alternativo")

    def _desenhar(self, linha: LinhaMensagem) -> None:
        t = self.texto
        modo_texto = self._imagens.modo_texto
        avatar = self._imagens.avatar(linha.avatar_id)
        self._inserir_imagem(avatar, linha.texto_avatar)

        tag_apelido = "apelido"
        mostrar_texto_cor = True
        if linha.cor is not None and not modo_texto:
            try:
                tag_apelido = self._tag_da_cor(linha.cor)
                mostrar_texto_cor = False
            except tk.TclError:
                tag_apelido = "apelido"
        t.insert(tk.END, linha.apelido, tag_apelido)
        if mostrar_texto_cor:
            t.insert(tk.END, " " + linha.texto_cor, "alternativo")
        t.insert(tk.END, "  " + linha.data_hora + "\n", "hora")

        if linha.ilegivel:
            t.insert(tk.END, linha.texto, ("corpo", "ilegivel"))
        elif linha.tipo == "figurinha":
            imagem = self._imagens.figurinha(linha.figurinha_id)
            self._inserir_imagem(imagem, linha.texto)
        elif modo_texto:
            t.insert(tk.END, linha.texto_alternativo, "corpo")
        else:
            try:
                t.insert(tk.END, linha.texto, "corpo")
            except tk.TclError:
                t.insert(tk.END, linha.texto_alternativo, "corpo")
        t.insert(tk.END, "\n\n")

    # ----- seletores ----------------------------------------------------------

    def alternar_seletor_emojis(self) -> None:
        if self._seletor_emojis is not None and self._seletor_emojis.winfo_exists():
            self._seletor_emojis.destroy()
            self._seletor_emojis = None
            return
        self._seletor_emojis = criar_seletor_emojis(
            self.winfo_toplevel(), self._emojis, self._acoes.emoji, modo_texto=self._imagens.modo_texto
        )

    def alternar_seletor_figurinhas(self) -> None:
        if self._seletor_figurinhas is not None and self._seletor_figurinhas.winfo_exists():
            self._seletor_figurinhas.destroy()
            self._seletor_figurinhas = None
            return
        self._seletor_figurinhas = criar_seletor_figurinhas(
            self.winfo_toplevel(), self._imagens, self._figurinhas, self._acoes.figurinha
        )

    def fechar_seletor_figurinhas(self) -> None:
        if self._seletor_figurinhas is not None and self._seletor_figurinhas.winfo_exists():
            self._seletor_figurinhas.destroy()
        self._seletor_figurinhas = None

    def fechar_seletores(self) -> None:
        if self._seletor_emojis is not None and self._seletor_emojis.winfo_exists():
            self._seletor_emojis.destroy()
        self._seletor_emojis = None
        self.fechar_seletor_figurinhas()


# ---------------------------------------------------------------------------
# Seletores (Toplevel com grid)
# ---------------------------------------------------------------------------

def criar_seletor_emojis(
    raiz: tk.Misc,
    emojis: Sequence[str],
    ao_escolher: Callable[[str], None],
    *,
    modo_texto: bool = False,
) -> tk.Toplevel:
    """Seletor_de_Emojis: ``Toplevel`` com um ``Button`` por emoji em
    ``grid(row, column)``, 6 linhas por 8 colunas. Escolher um emoji chama
    ``ao_escolher``; nada e enviado. Se o Tk nao desenha o emoji, o botao
    mostra os pontos de codigo (``U+1F600``)."""
    janela = tk.Toplevel(raiz)
    janela.title("Emojis")
    janela.transient(raiz)
    janela.resizable(False, False)
    janela.botoes = []  # type: ignore[attr-defined]
    for i, emoji in enumerate(emojis):
        linha, coluna = divmod(i, COLUNAS_EMOJIS)
        rotulo = codigo_emoji(emoji) if modo_texto else emoji
        try:
            botao = tk.Button(
                janela, text=rotulo, width=3 if not modo_texto else 9,
                command=lambda e=emoji: ao_escolher(e),
            )
        except tk.TclError:
            botao = tk.Button(janela, text=codigo_emoji(emoji), command=lambda e=emoji: ao_escolher(e))
        botao.grid(row=linha, column=coluna, padx=1, pady=1)
        janela.botoes.append(botao)  # type: ignore[attr-defined]
    if not emojis:
        ttk.Label(janela, text="Lista de emojis indisponível.", padding=12).grid(row=0, column=0)
    return janela


def criar_seletor_figurinhas(
    raiz: tk.Misc,
    imagens: CacheImagens,
    figurinhas: Sequence[str],
    ao_escolher: Callable[[str], None],
) -> tk.Toplevel:
    """Seletor de figurinhas: miniaturas em ``grid``; sem imagem, o botao mostra
    ``[figurinha: fig_03]``. Escolher uma figurinha chama ``ao_escolher``."""
    janela = tk.Toplevel(raiz)
    janela.title("Figurinhas")
    janela.transient(raiz)
    janela.resizable(False, False)
    janela.miniaturas = []  # type: ignore[attr-defined]  # referencias aos PhotoImage
    janela.botoes = []  # type: ignore[attr-defined]
    for i, id_ in enumerate(figurinhas):
        linha, coluna = divmod(i, COLUNAS_FIGURINHAS)
        imagem = imagens.figurinha(id_, LADO_MINIATURA)
        if imagem is not None:
            janela.miniaturas.append(imagem)  # type: ignore[attr-defined]
            botao = tk.Button(janela, image=imagem, command=lambda f=id_: ao_escolher(f))
        else:
            botao = tk.Button(janela, text=texto_figurinha(id_), command=lambda f=id_: ao_escolher(f))
        botao.grid(row=linha, column=coluna, padx=2, pady=2)
        janela.botoes.append(botao)  # type: ignore[attr-defined]
    if not figurinhas:
        ttk.Label(janela, text="Nenhuma figurinha disponível.", padding=12).grid(row=0, column=0)
    return janela
