/*
 * Своя libneuron_op_capability.so для дополнения ONNX Runtime NeuroPilot (onnxruntime-ep-neuropilot v0.3.0) — вместо
 * закрытой из его выпуска: у той нет никакой лицензии (ни в архиве, ни в самой библиотеке), а дополнение без неё не
 * стартует (dlopen при создании). Решение владельца 10.11 — «Своя заглушка».
 *
 * Тот же интерфейс (op_capability_abi/neuron_op_capability_abi.h, MIT © Microsoft — подключается из исходников
 * дополнения при сборке, в репозиторий не кладётся), но без чужих знаний о поддержке операций:
 *   - для каждой операции «ограничения есть» (HasCapability → TRUE): тогда дополнение выполняет свои открытые проверки
 *     типов и входов (HasSupportedInputs/Outputs — с правками Falar для int16) — их мы видим и правим сами;
 *   - сама проверка узла — «поддержан» (IsNodeUnitTypeSupported, IsNodeSupported → TRUE);
 *   - окончательно решает рантайм Neuron телефона: при пробной компиляции дополнение отдаёт процессору части, которые
 *     он не собрал, а с NEURON_FLAG_SKIP_CAPABILITY_PROBE=1 отказ — ошибка сессии (Falar тогда распознаёт процессором
 *     и запоминает npu_fail).
 * Граф, который дополнение передаёт сериализованным, заглушке не нужен: CreateGraph возвращает непустой маркер
 * (NULL значит «повреждённый граф»), ReleaseGraph — ничего не делает.
 *
 * Сборка — bench/npu/neuropilot-ep/build.sh (NDK, -fvisibility=hidden, экспорт только этих шести функций).
 */
#include <stddef.h>
#include <stdint.h>

#include "neuron_op_capability_abi.h"

static char g_marker;   /* общий непустой маркер «графа» — заглушке он не нужен */

NEURON_CAP_API uint32_t NeuronCapGetAbiVersion(void) { return NEURON_CAP_ABI_VERSION; }

NEURON_CAP_API NeuronCapGraph* NeuronCapCreateGraph(const void* bytes, size_t length) {
  if (bytes == NULL && length != 0) return NULL;
  return (NeuronCapGraph*)&g_marker;
}

NEURON_CAP_API void NeuronCapReleaseGraph(NeuronCapGraph* graph) { (void)graph; }

NEURON_CAP_API int32_t NeuronCapHasCapability(const char* op_type) {
  return op_type == NULL ? NEURON_CAP_ERROR : NEURON_CAP_TRUE;
}

NEURON_CAP_API int32_t NeuronCapIsNodeUnitTypeSupported(NeuronCapGraph* graph, int32_t node_index) {
  if (graph == NULL || node_index < 0) return NEURON_CAP_ERROR;
  return NEURON_CAP_TRUE;
}

NEURON_CAP_API int32_t NeuronCapIsNodeSupported(NeuronCapGraph* graph, int32_t node_index,
                                                const NeuronCapParams* params) {
  if (graph == NULL || node_index < 0 || params == NULL || params->struct_size != sizeof(NeuronCapParams))
    return NEURON_CAP_ERROR;
  return NEURON_CAP_TRUE;
}
