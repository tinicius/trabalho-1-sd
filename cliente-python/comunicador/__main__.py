"""Ponto de entrada do Cliente_Terceiro: ``python -m comunicador``.

Opcoes: ``--host``, ``--porta``, ``--recursos`` e ``--modo-texto``
(``python -m comunicador --help``).
"""
import sys


def main() -> int:
    try:
        from comunicador.gui.app import principal
    except ImportError as e:  # Python sem Tkinter (_tkinter ausente)
        print(f"Tkinter não está disponível neste Python: {e}", file=sys.stderr)
        return 1
    return principal()


if __name__ == "__main__":
    raise SystemExit(main())
