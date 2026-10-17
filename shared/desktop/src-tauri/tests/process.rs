use std::{io::Write, sync::Arc};
use torikomi_desktop::process::{execute, execute_captured, Job};

#[test]
#[ignore]
fn child_zero_exit_error_fixture() {
    println!("[]");
    eprintln!("[tiktok][error] 403 Forbidden");
}

#[test]
fn zero_exit_diagnostics_are_available_without_corrupting_json_stdout() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let exe = std::env::current_exe().unwrap();
    let args = [
        "--exact",
        "child_zero_exit_error_fixture",
        "--ignored",
        "--nocapture",
    ]
    .map(String::from);
    let result = runtime
        .block_on(torikomi_desktop::process::execute_output(
            exe.to_str().unwrap(),
            &args,
            Arc::new(Job::default()),
            4096,
            |_| {},
        ))
        .unwrap();
    assert!(result.stdout.contains("[]"));
    assert!(!result.stdout.contains("403"));
    assert!(result.stderr.contains("403 Forbidden"));
}

#[test]
#[ignore]
fn child_fixture() {
    println!("literal-output $(never-execute)");
}

#[test]
#[ignore]
fn child_wait_fixture() {
    std::thread::sleep(std::time::Duration::from_secs(30));
}

#[test]
#[ignore]
fn child_failure_fixture() {
    println!("completed-photo-before-failure");
    std::io::stdout().flush().unwrap();
    eprintln!("fixture engine failure");
    std::process::exit(7);
}

#[test]
#[ignore]
fn child_progress_fixture() {
    println!("completed-photo-before-cancellation");
    std::io::stdout().flush().unwrap();
    std::thread::sleep(std::time::Duration::from_secs(30));
}

#[test]
fn captured_failure_retains_completed_stdout_and_engine_error() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let exe = std::env::current_exe()
        .unwrap()
        .to_string_lossy()
        .into_owned();
    let args = [
        "--exact",
        "child_failure_fixture",
        "--ignored",
        "--nocapture",
    ]
    .map(String::from);
    let error = runtime
        .block_on(execute_captured(
            &exe,
            &args,
            Arc::new(Job::default()),
            4096,
            |_| {},
        ))
        .unwrap_err();
    assert!(error.message.contains("fixture engine failure"));
    assert!(error.output.contains("completed-photo-before-failure\n"));
}

#[test]
fn captured_cancellation_retains_completed_stdout() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let exe = std::env::current_exe()
        .unwrap()
        .to_string_lossy()
        .into_owned();
    let args = [
        "--exact",
        "child_progress_fixture",
        "--ignored",
        "--nocapture",
    ]
    .map(String::from);
    let job = Arc::new(Job::default());
    let cancel = job.clone();
    let error = runtime
        .block_on(execute_captured(&exe, &args, job, 4096, move |text| {
            if text.contains("completed-photo-before-cancellation") {
                cancel.cancel();
            }
        }))
        .unwrap_err();
    assert!(error.message.contains("cancelled"));
    assert!(error
        .output
        .contains("completed-photo-before-cancellation\n"));
}

#[test]
fn process_failure_preserves_the_engine_error() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let exe = std::env::current_exe()
        .unwrap()
        .to_string_lossy()
        .into_owned();
    let args = [
        "--exact",
        "child_failure_fixture",
        "--ignored",
        "--nocapture",
    ]
    .map(String::from);
    let error = runtime
        .block_on(execute(&exe, &args, Arc::new(Job::default()), 4096, |_| {}))
        .unwrap_err();
    assert!(error.contains("fixture engine failure"));
}

#[test]
fn process_captures_literal_output_and_rejects_output_over_limit() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let exe = std::env::current_exe()
        .unwrap()
        .to_string_lossy()
        .into_owned();
    let args = ["--exact", "child_fixture", "--ignored", "--nocapture"].map(String::from);
    runtime.block_on(async {
        let output = execute(&exe, &args, Arc::new(Job::default()), 4096, |_| {})
            .await
            .unwrap();
        assert!(output.contains("literal-output $(never-execute)"));
        assert!(execute(&exe, &args, Arc::new(Job::default()), 2, |_| {})
            .await
            .is_err());
        let error = execute_captured(&exe, &args, Arc::new(Job::default()), 2, |_| {})
            .await
            .unwrap_err();
        assert!(error.message.contains("safety limit"));
        assert!(error.output.len() <= 2);
    });
}

#[test]
fn cancellation_stops_a_running_process() {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap();
    let exe = std::env::current_exe()
        .unwrap()
        .to_string_lossy()
        .into_owned();
    let args = ["--exact", "child_wait_fixture", "--ignored", "--nocapture"].map(String::from);
    runtime.block_on(async {
        let job = Arc::new(Job::default());
        let cancel = job.clone();
        let task = async move {
            tokio::time::sleep(std::time::Duration::from_millis(100)).await;
            cancel.cancel();
        };
        let (result, _) = tokio::join!(execute(&exe, &args, job, 4096, |_| {}), task);
        assert!(result.unwrap_err().contains("cancelled"));
    });
}
