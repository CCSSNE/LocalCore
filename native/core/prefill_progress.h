#pragma once

#include "localcore_core_api.h"
#include "ggml.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <stdexcept>
#include <unordered_map>
#include <vector>

// Request-scoped, token-weighted graph completion, not an elapsed-time estimate.
// A token batch earns its share only as its actual compute nodes finish. View-only
// nodes do not count. 100% is published only after the corresponding helper succeeds.
class PrefillProgress {
public:
    struct Phase {
        const char * name;
        int64_t total = 0;
        int64_t completed = 0;
        int32_t last_value = -1;
    };

    struct Graph {
        PrefillProgress & owner;
        bool vision;
        Phase * phase = nullptr;
        int64_t tokens = 0;
        int nodes = 0;
        int done = 0;
        std::unordered_map<ggml_tensor *, int> checkpoints;

        static void begin(ggml_cgraph * graph, int64_t tokens, void * opaque) {
            static_cast<Graph *>(opaque)->prepare(graph, tokens);
        }

        static bool eval(ggml_tensor * tensor, bool ask, void * opaque) {
            auto & self = *static_cast<Graph *>(opaque);
            if (!self.owner.active) return ask ? false : true;
            const auto found = self.checkpoints.find(tensor);
            if (ask) return found != self.checkpoints.end();
            self.owner.check_cancelled();
            if (found == self.checkpoints.end() || found->second <= self.done) {
                throw std::runtime_error("进度回调节点顺序异常");
            }
            self.done = found->second;
            const double partial = double(self.tokens) * self.done / self.nodes;
            self.owner.publish(*self.phase, self.phase->completed + partial);
            if (self.done == self.nodes) self.phase->completed += self.tokens;
            return true;
        }

        void verify() const {
            if (phase && done != nodes) throw std::runtime_error("计算结束但节点进度不完整");
        }

        void prepare(ggml_cgraph * graph, int64_t work_tokens) {
            checkpoints.clear();
            if (!owner.active) return;
            owner.check_cancelled();
            verify();
            // The next LLM graph follows successful visual encoding and output copy.
            if (!vision && owner.image_chunk) owner.finish(owner.image);
            phase = vision ? &owner.image : owner.image_chunk ? &owner.image_context : &owner.context;
            tokens = vision ? owner.chunk_tokens : work_tokens;
            if (tokens <= 0 || phase->completed + tokens > phase->total) {
                throw std::runtime_error("计算图 token 数与本次进度总量不一致");
            }
            std::vector<ggml_tensor *> compute_nodes;
            for (int i = 0; i < ggml_graph_n_nodes(graph); ++i) {
                ggml_tensor * node = ggml_graph_node(graph, i);
                switch (node->op) {
                    case GGML_OP_NONE: case GGML_OP_RESHAPE: case GGML_OP_VIEW:
                    case GGML_OP_PERMUTE: case GGML_OP_TRANSPOSE: break;
                    default: compute_nodes.push_back(node); break;
                }
            }
            nodes = static_cast<int>(compute_nodes.size());
            done = 0;
            if (nodes == 0) throw std::runtime_error("推理计算图没有可计量的计算节点");
            // About one checkpoint per 1% of a graph. The existing scheduler batches
            // intervening nodes and synchronizes before its ask=false callback.
            const int stride = std::max(1, (nodes + 99) / 100);
            for (int i = stride; i < nodes; i += stride) checkpoints.emplace(compute_nodes[i - 1], i);
            checkpoints.emplace(compute_nodes.back(), nodes);
            owner.publish(*phase, phase->completed, true);
        }
    };

    explicit PrefillProgress(std::atomic_bool & cancelled)
        : llm{*this, false}, vision{*this, true}, cancelled(cancelled) {}

    Graph llm;
    Graph vision;
    Phase context{"context"};
    Phase image{"image"};
    Phase image_context{"image_context"};
    bool active = false;
    bool image_chunk = false;
    int64_t chunk_tokens = 0;

    void start(localcore_progress_callback callback, void * user_data) {
        this->callback = callback;
        this->user_data = user_data;
        active = callback != nullptr;
        context = {"context"};
        image = {"image"};
        image_context = {"image_context"};
        llm.phase = vision.phase = nullptr;
        llm.checkpoints.clear();
        vision.checkpoints.clear();
        current = nullptr;
        image_chunk = false;
        chunk_tokens = 0;
    }

    void stop() {
        active = false;
        callback = nullptr;
        user_data = nullptr;
        llm.phase = vision.phase = nullptr;
        llm.checkpoints.clear();
        vision.checkpoints.clear();
    }

    void preparing(const char * phase) {
        if (active) callback(phase, 0, 0, user_data);
    }

    void select_chunk(bool is_image, int64_t tokens) {
        check_cancelled();
        image_chunk = is_image;
        chunk_tokens = tokens;
        if (active) publish(is_image ? image : context, (is_image ? image : context).completed, true);
    }

    void finish_chunk() {
        check_cancelled();
        if (!active) return;
        llm.verify();
        vision.verify();
        finish(image_chunk ? image_context : context);
    }

    void verify_complete() {
        if (!active) return;
        for (Phase * phase : {&context, &image, &image_context}) {
            if (phase->completed != phase->total) throw std::runtime_error("预填充完成量与总量不一致");
        }
    }

    void check_cancelled() const {
        if (cancelled.load(std::memory_order_relaxed)) throw std::runtime_error("推理已取消");
    }

private:
    std::atomic_bool & cancelled;
    localcore_progress_callback callback = nullptr;
    void * user_data = nullptr;
    Phase * current = nullptr;
    std::chrono::steady_clock::time_point last_report;

    void finish(Phase & phase) {
        if (phase.total > 0 && phase.completed == phase.total && phase.last_value != 10000) {
            publish(phase, phase.completed, true, true);
        }
    }

    void publish(Phase & phase, double completed, bool force = false, bool finished = false) {
        if (!active || phase.total == 0) return;
        const int32_t value = finished ? 10000 : std::min(9999, static_cast<int>(std::floor(completed / phase.total * 10000)));
        const auto now = std::chrono::steady_clock::now();
        const bool switched = current != &phase;
        if (!switched && !force && (value == phase.last_value || now - last_report < std::chrono::milliseconds(80))) return;
        current = &phase;
        phase.last_value = value;
        last_report = now;
        callback(phase.name, value, 10000, user_data);
    }
};
