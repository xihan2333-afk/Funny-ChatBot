<template>
  <div class="auth-page">
    <el-card class="auth-card" shadow="always">
      <template #header>
        <div class="card-title">修改管理员口令</div>
      </template>
      <el-alert
          v-if="authStore.mustChange"
          type="warning"
          show-icon
          :closable="false"
          title="首次登录必须修改默认口令后，才能访问管理平台其他功能。"
          style="margin-bottom: 16px"
      />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="handleSubmit">
        <el-form-item label="原口令" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入原口令"/>
        </el-form-item>
        <el-form-item label="新口令" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="请输入新口令（至少 8 位）"/>
        </el-form-item>
        <el-form-item label="确认新口令" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="请再次输入新口令"/>
        </el-form-item>
        <div class="btn-row">
          <el-button v-if="!authStore.mustChange" @click="router.back()">返回</el-button>
          <el-button type="primary" :loading="loading" @click="handleSubmit">
            确认修改并重新登录
          </el-button>
        </div>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import {reactive, ref} from 'vue'
import {useRouter} from 'vue-router'
import {ElMessage, type FormInstance, type FormRules} from 'element-plus'
import {useAuthStore} from '@/stores/auth'

const router = useRouter()
const authStore = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const rules: FormRules = {
  oldPassword: [{required: true, message: '请输入原口令', trigger: 'blur'}],
  newPassword: [
    {required: true, message: '请输入新口令', trigger: 'blur'},
    {min: 8, message: '新口令长度至少 8 位', trigger: 'blur'},
  ],
  confirmPassword: [
    {required: true, message: '请确认新口令', trigger: 'blur'},
    {
      validator: (_rule, value, callback) => {
        if (value !== form.newPassword) {
          callback(new Error('两次输入的新口令不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await authStore.changePassword(form.oldPassword, form.newPassword)
    ElMessage.success('口令修改成功，请使用新口令重新登录')
    await router.replace('/login')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f0f2f5;
}

.auth-card {
  width: 440px;
}

.card-title {
  font-size: 18px;
  font-weight: 600;
}

.btn-row {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
