#pragma once

#include <stdexcept>
#include <string>
#include <utility>

namespace design {

struct CompilationDatabase;
struct CompileCommand;
struct IncludeGraph;
struct UserConfig;

enum class HeuristicStrategy {
  use_sibling,
  use_parent_project,
  use_nvcc,
  not_confident
};

[[nodiscard]] inline HeuristicStrategy HeuristicRules(const std::string& extension, bool has_sibling_cpp, bool in_include_dir) {
  if (((extension == ".h") || (extension == ".hpp") || (extension == ".inl")) && (has_sibling_cpp)) {
    return HeuristicStrategy::use_sibling;
  }
  if (((extension == ".h") || (extension == ".hpp")) && (in_include_dir)) {
    return HeuristicStrategy::use_parent_project;
  }
  if (extension == ".cu") {
    return HeuristicStrategy::use_nvcc;
  }
  return HeuristicStrategy::not_confident;
}

// Body contains constructs that are not safely lowered to C++ yet.
[[nodiscard]] CompileCommand SelectCompileCommand(const std::string& file_path, const UserConfig& user_config, const CompilationDatabase& cdb, const IncludeGraph& include_graph);

class OrderProcessing {
public:
  enum class State {
    Pending,
    Charging,
    Confirmed,
    Shipping,
    Delivered,
    Cancelled
  };

  OrderProcessing(
      std::string order_id,
      double amount,
      std::string payment_ref,
      std::string tracking,
      State state = State::Pending
  )
      : order_id_(std::move(order_id)),
        amount_(amount),
        payment_ref_(std::move(payment_ref)),
        tracking_(std::move(tracking)),
        state_(state) {}

  [[nodiscard]] State state() const noexcept { return state_; }

  [[nodiscard]] bool is_terminal() const noexcept { return state_ == State::Delivered || state_ == State::Cancelled; }

  [[nodiscard]] OrderProcessing transit() const {
    switch (state_) {
      case State::Charging:
        if (chargeOk()) {
          return with_state(State::Confirmed);
        }
        if (chargeFail()) {
          return with_state(State::Cancelled);
        }
        break;
      case State::Confirmed:
        if (ship()) {
          return with_state(State::Shipping);
        }
        break;
      case State::Pending:
        if ((pay() && (amount_ > 0))) {
          return with_state(State::Charging);
        }
        if (cancel()) {
          return with_state(State::Cancelled);
        }
        break;
      case State::Shipping:
        if (deliver()) {
          return with_state(State::Delivered);
        }
        break;
    }
      throw std::runtime_error("Invalid transition from {this}");
  }

protected:
  virtual bool cancel() const { return false; }
  virtual bool chargeFail() const { return false; }
  virtual bool chargeOk() const { return false; }
  virtual bool deliver() const { return false; }
  virtual bool pay() const { return false; }
  virtual bool ship() const { return false; }

private:
  [[nodiscard]] OrderProcessing with_state(State state) const {
    auto next = *this;
    next.state_ = state;
    return next;
  }

  std::string order_id_;
  double amount_;
  std::string payment_ref_;
  std::string tracking_;
  State state_;
};

} // namespace design
