"""Запуск программ агента внутри встроенного Python (Chaquopy)."""
import contextlib
import io
import os
import runpy
import sys
import traceback


def run(code, cwd, file, args):
    """Выполняет код или файл. args — строка с аргументами через перевод строки. Возвращает (вывод, код_выхода)."""
    out = io.StringIO()
    old_cwd = os.getcwd()
    old_argv = sys.argv
    old_stdin = sys.stdin
    modules_before = set(sys.modules)
    exit_code = 0

    sys.argv = [file or "<agent>"] + [a for a in str(args or "").split("\n") if a]
    sys.stdin = io.StringIO("")  # input() не должен зависать
    sys.path.insert(0, cwd)
    try:
        os.chdir(cwd)
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            try:
                if file:
                    runpy.run_path(file, run_name="__main__")
                else:
                    exec(compile(code, "<agent>", "exec"), {"__name__": "__main__"})
            except SystemExit as e:
                if e.code not in (None, 0):
                    exit_code = e.code if isinstance(e.code, int) else 1
                    if not isinstance(e.code, int):
                        print(e.code)
            except BaseException:
                exit_code = 1
                traceback.print_exc()
    finally:
        try:
            os.chdir(old_cwd)
        except OSError:
            pass
        sys.argv = old_argv
        sys.stdin = old_stdin
        if cwd in sys.path:
            sys.path.remove(cwd)
        # Модули проекта не должны «залипать» между запусками
        for name in set(sys.modules) - modules_before:
            mod = sys.modules.get(name)
            path = getattr(mod, "__file__", None) or ""
            if path.startswith(cwd):
                sys.modules.pop(name, None)
    return out.getvalue(), exit_code
