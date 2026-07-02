const machineCode = document.getElementById("machineCode");
const customer = document.getElementById("customer");
const expiresAt = document.getElementById("expiresAt");
const permanent = document.getElementById("permanent");
const pickDate = document.getElementById("pickDate");
const expiryPreview = document.getElementById("expiryPreview");
const dateDialog = document.getElementById("dateDialog");
const modalDate = document.getElementById("modalDate");
const confirmDate = document.getElementById("confirmDate");
const cancelDate = document.getElementById("cancelDate");
const licenseKey = document.getElementById("licenseKey");
const message = document.getElementById("message");
const generate = document.getElementById("generate");
const copy = document.getElementById("copy");
const clear = document.getElementById("clear");

permanent.addEventListener("change", () => {
  if (permanent.checked) expiresAt.value = "";
  updateExpiryPreview();
});

pickDate.addEventListener("click", () => {
  permanent.checked = false;
  modalDate.value = expiresAt.value || today();
  dateDialog.classList.remove("hidden");
  modalDate.focus();
  updateExpiryPreview();
});

confirmDate.addEventListener("click", () => {
  if (!modalDate.value) return;
  permanent.checked = false;
  expiresAt.value = modalDate.value;
  dateDialog.classList.add("hidden");
  updateExpiryPreview();
});

cancelDate.addEventListener("click", closeDateDialog);
dateDialog.addEventListener("click", (event) => {
  if (event.target === dateDialog) closeDateDialog();
});

document.addEventListener("keydown", (event) => {
  if (event.key === "Escape") closeDateDialog();
});

generate.addEventListener("click", async () => {
  if (!permanent.checked && !expiresAt.value) {
    message.textContent = "请选择到期日期，或勾选永久授权";
    message.className = "error";
    return;
  }
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
  updateExpiryPreview();
  licenseKey.value = "";
  message.textContent = "";
  message.className = "";
  machineCode.focus();
});

function closeDateDialog() {
  dateDialog.classList.add("hidden");
}

function updateExpiryPreview() {
  if (permanent.checked) {
    pickDate.textContent = "选择到期日期";
    expiryPreview.textContent = "当前：永久授权";
    return;
  }
  pickDate.textContent = expiresAt.value ? `到期：${formatDate(expiresAt.value)}` : "选择到期日期";
  expiryPreview.textContent = expiresAt.value ? `当前到期日期：${formatDate(expiresAt.value)}` : "当前：未选择到期日期";
}

function today() {
  return new Date().toISOString().slice(0, 10);
}

function formatDate(value) {
  const [year, month, day] = String(value).split("-");
  return year && month && day ? `${year}-${month}-${day}` : value;
}

updateExpiryPreview();
machineCode.focus();
