#!/usr/bin/env python3
"""Gera Chaves_de_Canal para a Cifra_Manual.

Cada chave tem 32 bytes aleatorios (os.urandom), impressos como 64
caracteres hexadecimais.

Uso:
    python3 scripts/gerar_chave.py          # imprime uma chave
    python3 scripts/gerar_chave.py --par    # imprime o par de chaves no formato
                                            # de config/chaves.properties

A chave gerada deve ser entregue por meio distinto da mensagem (copia manual
do arquivo), nunca pelo socket. Nao versione o arquivo chaves.properties real.
"""

import argparse
import os
import sys

TAMANHO_BYTES = 32


def gerar_chave() -> str:
    """Retorna 32 bytes aleatorios como 64 caracteres hexadecimais."""
    return os.urandom(TAMANHO_BYTES).hex()


def gerar_par() -> tuple:
    """Retorna duas chaves distintas (transito, repositorio)."""
    transito = gerar_chave()
    repositorio = gerar_chave()
    while repositorio == transito:  # praticamente impossivel; garante a distincao
        repositorio = gerar_chave()
    return transito, repositorio


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Gera chaves de 32 bytes aleatorios em hexadecimal (64 caracteres)."
    )
    parser.add_argument(
        "--par",
        action="store_true",
        help="imprime duas chaves distintas (transito e repositorio) "
        "no formato de config/chaves.properties",
    )
    args = parser.parse_args(argv)

    if args.par:
        transito, repositorio = gerar_par()
        print(f"chave.transito={transito}")
        print(f"chave.repositorio={repositorio}")
    else:
        print(gerar_chave())
    return 0


if __name__ == "__main__":
    sys.exit(main())
