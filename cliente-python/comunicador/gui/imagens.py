"""Carga dos PNG de avatares e figurinhas como ``tk.PhotoImage``.

O ``PhotoImage`` so existe enquanto alguem guarda uma referencia a ele; por
isso o ``CacheImagens`` mantem todos em um dicionario. Quando a imagem
nao carrega (arquivo ausente, PNG invalido, id fora do formato, Tk sem
suporte), o cache devolve ``None`` e quem desenha usa a representacao
textual de ``modelo``. No modo texto nenhuma imagem e carregada.

Os identificadores vem da rede e sao validados por ``config.caminho_avatar`` e
``config.caminho_figurinha`` antes de formar o caminho do arquivo.
"""
from __future__ import annotations

import tkinter as tk
from pathlib import Path
from typing import Callable, Optional, Union

from comunicador.gui.modelo import reducao_para_caber
from comunicador.nucleo import config
from comunicador.nucleo.config import ErroDeConfiguracao

LADO_AVATAR_MENSAGEM = 48
"""Lado maximo do avatar ao lado da mensagem, em pixels."""

LADO_FIGURINHA_MENSAGEM = 80
"""Lado maximo da figurinha dentro da sala, em pixels."""

LADO_MINIATURA = 56
"""Lado maximo das miniaturas nos seletores, em pixels."""


class CacheImagens:
    """Carrega e guarda os ``PhotoImage`` (inclusive as falhas, para nao repetir)."""

    def __init__(
        self,
        mestre: tk.Misc,
        recursos: Optional[Union[str, Path]] = None,
        *,
        modo_texto: bool = False,
    ) -> None:
        self._mestre = mestre
        self._recursos = recursos
        self._modo_texto = modo_texto
        self._imagens: dict[tuple[str, str, int], Optional[tk.PhotoImage]] = {}

    @property
    def modo_texto(self) -> bool:
        return self._modo_texto

    def avatar(self, id_: Optional[str], lado: int = LADO_AVATAR_MENSAGEM) -> Optional[tk.PhotoImage]:
        return self._obter("avatar", id_, lado, config.caminho_avatar)

    def figurinha(self, id_: Optional[str], lado: int = LADO_FIGURINHA_MENSAGEM) -> Optional[tk.PhotoImage]:
        return self._obter("figurinha", id_, lado, config.caminho_figurinha)

    def _obter(
        self,
        tipo: str,
        id_: Optional[str],
        lado: int,
        caminho: Callable[..., Path],
    ) -> Optional[tk.PhotoImage]:
        if self._modo_texto or id_ is None:
            return None
        chave = (tipo, id_, lado)
        if chave not in self._imagens:
            self._imagens[chave] = self._carregar(id_, lado, caminho)
        return self._imagens[chave]

    def _carregar(self, id_: str, lado: int, caminho: Callable[..., Path]) -> Optional[tk.PhotoImage]:
        try:
            dados = config.ler_png(caminho(id_, self._recursos))
            imagem = tk.PhotoImage(master=self._mestre, data=dados)
            fator = reducao_para_caber(imagem.width(), imagem.height(), lado)
            if fator > 1:
                imagem = imagem.subsample(fator, fator)
            return imagem
        except (ErroDeConfiguracao, tk.TclError):
            return None
