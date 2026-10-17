use std::{
    process::Stdio,
    sync::{
        atomic::{AtomicBool, AtomicU32, Ordering},
        Arc,
    },
};
use tokio::io::{AsyncRead, AsyncReadExt};

#[derive(Debug)]
pub struct ProcessError {
    pub message: String,
    pub output: String,
}

#[derive(Debug)]
pub struct ProcessOutput {
    pub stdout: String,
    pub stderr: String,
}

pub async fn execute(
    program: &str,
    args: &[String],
    job: Arc<Job>,
    limit: usize,
    progress: impl Fn(String) + Send + Sync + 'static,
) -> Result<String, String> {
    execute_captured(program, args, job, limit, progress)
        .await
        .map_err(|error| error.message)
}

pub struct Job {
    pub cancelled: AtomicBool,
    pub wake: tokio::sync::Notify,
    pid: AtomicU32,
}
impl Default for Job {
    fn default() -> Self {
        Self {
            cancelled: AtomicBool::new(false),
            wake: tokio::sync::Notify::new(),
            pid: AtomicU32::new(0),
        }
    }
}
impl Job {
    pub fn cancel(&self) {
        self.cancelled.store(true, Ordering::SeqCst);
        self.wake.notify_one();
        stop_tree(self.pid.load(Ordering::SeqCst));
    }
}

fn stop_tree(pid: u32) {
    if pid == 0 {
        return;
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let _ = std::process::Command::new("taskkill")
            .args(["/PID", &pid.to_string(), "/T", "/F"])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(0x08000000)
            .status();
    }
    #[cfg(unix)]
    unsafe {
        // Each engine invocation owns its group, including any FFmpeg children.
        libc::kill(-(pid as i32), libc::SIGTERM);
    }
}

async fn read_output(
    mut pipe: impl AsyncRead + Unpin,
    output: &mut Vec<u8>,
    limit: usize,
    tail: bool,
    progress: impl Fn(String),
) -> Result<(), String> {
    let mut buffer = [0; 8192];
    loop {
        let count = pipe.read(&mut buffer).await.map_err(|e| e.to_string())?;
        if count == 0 {
            return Ok(());
        }
        if !tail && count > limit.saturating_sub(output.len()) {
            output.extend_from_slice(&buffer[..limit.saturating_sub(output.len())]);
            return Err("Engine output exceeds the safety limit".into());
        }
        output.extend_from_slice(&buffer[..count]);
        if output.len() > limit {
            output.drain(..output.len() - limit);
        }
        progress(
            String::from_utf8_lossy(&buffer[..count])
                .chars()
                .rev()
                .take(1000)
                .collect::<String>()
                .chars()
                .rev()
                .collect(),
        );
    }
}

pub async fn execute_captured(
    program: &str,
    args: &[String],
    job: Arc<Job>,
    limit: usize,
    progress: impl Fn(String) + Send + Sync + 'static,
) -> Result<String, ProcessError> {
    execute_output(program, args, job, limit, progress)
        .await
        .map(|output| output.stdout)
}

pub async fn execute_output(
    program: &str,
    args: &[String],
    job: Arc<Job>,
    limit: usize,
    progress: impl Fn(String) + Send + Sync + 'static,
) -> Result<ProcessOutput, ProcessError> {
    let empty_error = |message| ProcessError {
        message,
        output: String::new(),
    };
    if job.cancelled.load(Ordering::SeqCst) {
        return Err(empty_error("Operation cancelled".into()));
    }
    let mut command = tokio::process::Command::new(program);
    command
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    #[cfg(windows)]
    command.creation_flags(0x08000000);
    #[cfg(unix)]
    command.process_group(0);
    let mut child = command.spawn().map_err(|error| {
        empty_error(if error.kind() == std::io::ErrorKind::NotFound {
            "Select a valid installed engine executable in Settings".to_owned()
        } else {
            error.to_string()
        })
    })?;
    job.pid.store(child.id().unwrap_or(0), Ordering::SeqCst);
    let stdout = child
        .stdout
        .take()
        .ok_or_else(|| empty_error("Could not read engine output".into()))?;
    let stderr = child
        .stderr
        .take()
        .ok_or_else(|| empty_error("Could not read engine errors".into()))?;
    let mut output = Vec::new();
    let mut errors = Vec::new();
    let result = tokio::select! {
        biased;
        _ = job.wake.notified() => Err("Operation cancelled".into()),
        result = async {
            let (status, (), ()) = tokio::try_join!(
                async { child.wait().await.map_err(|e| e.to_string()) },
                read_output(stdout, &mut output, limit, false, progress),
                read_output(stderr, &mut errors, 8192, true, |_| {}),
            )?;
            if !status.success() { return Err(if errors.is_empty() { format!("Engine exited with {status}") } else { String::from_utf8_lossy(&errors).into_owned() }); }
            Ok(())
        } => result,
    };
    if result.is_err() || job.cancelled.load(Ordering::SeqCst) {
        stop_tree(job.pid.load(Ordering::SeqCst));
        let _ = child.kill().await;
    }
    job.pid.store(0, Ordering::SeqCst);
    let result = if job.cancelled.load(Ordering::SeqCst) {
        Err("Operation cancelled".into())
    } else {
        result
    };
    result.map_err(|message| ProcessError {
        message,
        output: String::from_utf8_lossy(&output).into_owned(),
    })?;
    let stdout = String::from_utf8(output).map_err(|error| ProcessError {
        message: "Engine returned invalid UTF-8".into(),
        output: String::from_utf8_lossy(error.as_bytes()).into_owned(),
    })?;
    Ok(ProcessOutput {
        stdout,
        stderr: String::from_utf8_lossy(&errors).into_owned(),
    })
}
