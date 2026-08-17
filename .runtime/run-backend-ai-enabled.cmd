@echo off
cd /d D:\Three-fees
powershell.exe -NoProfile -ExecutionPolicy Bypass -File D:\Three-fees\.runtime\start-backend-8080.ps1 > D:\Three-fees\.runtime\backend-ai-enabled.out.log 2> D:\Three-fees\.runtime\backend-ai-enabled.err.log
