const machineCode = document.getElementById("machineCode");
const customer = document.getElementById("customer");
const expiresAt = document.getElementById("expiresAt");
const permanent = document.getElementById("permanent");
const licenseKey = document.getElementById("licenseKey");
const message = document.getElementById("message");
const generate = document.getElementById("generate");
const copy = document.getElementById("copy");
const clear = document.getElementById("clear");

permanent.addEventListener("change", () => {
  expiresAt.disabled = permanent.checked;
  if (permanent.checked) expiresAt.value = "";
});

generate.addEventListener("click", async () => {
  message.textContent = "正在生成...";
  message.className = "";
  const result = await window.bridgeKeygen.generateLicense({
    machineCode: machineCode.value,
    customer: customer.value,
    expiresAt: permanent.checked ? "" : expiresAt.value
  });

  if (!result.ok) {
    licenseKey.value = "";
    message.textContent = result.message;
    message.className = "error";
    return;
  }

  licenseKey.value = result.licenseKey;
  message.textContent = result.payload.expiresAt ? `已生成，到期：${new Date(result.payload.expiresAt).toLocaleDateString()}` : "已生成，永久授权";
  message.className = "ok";
});

copy.addEventListener("click", async () => {
  if (!licenseKey.value) {
    message.textContent = "还没有注册码可复制";
    message.className = "error";
    return;
  }
  await window.bridgeKeygen.copyText(licenseKey.value);
  message.textContent = "注册码已复制";
  message.className = "ok";
});

clear.addEventListener("click", () => {
  machineCode.value = "";
  customer.value = "";
  expiresAt.value = "";
  permanent.checked = true;
  expiresAt.disabled = true;
  licenseKey.value = "";
  message.textContent = "";
  message.className = "";
  machineCode.focus();
});

expiresAt.disabled = true;
machineCode.focus();
