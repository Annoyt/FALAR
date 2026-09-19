"""Эмуляция ресурсов POCO X7 Pro (Dimensity 8400-Ultra, 12 ГБ RAM).

Профили:
  poco-cpu  — реалистичный: 4 потока CPU, лимит RAM ~8 ГБ, int8/Q4 квантование
  poco-gpu  — оптимистичный: без жёстких лимитов (прокси для Vulkan/Mali на телефоне)
"""
import os
import resource

POCO_BIG_CORES = 4          # Cortex-A725
POCO_RAM_GB = 8             # из 12 ГБ часть съедает Android

_PROFILE = os.environ.get("POCO_PROFILE", "poco-cpu")


def apply(profile: str | None = None) -> str:
    profile = profile or _PROFILE
    if profile == "poco-cpu":
        os.environ["OMP_NUM_THREADS"] = str(POCO_BIG_CORES)
        os.environ["CT2_VERBOSE"] = "0"
        limit_bytes = POCO_RAM_GB * 1024 ** 3
        try:
            resource.setrlimit(resource.RLIMIT_AS, (limit_bytes, limit_bytes))
        except (ValueError, OSError):
            pass
    return profile


def cpu_threads() -> int:
    return POCO_BIG_CORES if _PROFILE == "poco-cpu" else (os.cpu_count() or 8)


if __name__ == "__main__":
    p = apply()
    print(f"profile={p} cpu_threads={cpu_threads()} ram_limit_gb={POCO_RAM_GB if p == 'poco-cpu' else 'none'}")
