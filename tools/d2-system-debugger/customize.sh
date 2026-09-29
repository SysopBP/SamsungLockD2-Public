#!/system/bin/sh
ui_print 'D2 System Server Debugger 1.1.0'
ui_print 'Captures boot logs plus continuous crash evidence; exports to Downloads.'
ui_print 'Use Action to collect now or recover a previous boot capture.'
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in "$MODPATH"/*.sh; do
  set_perm "$script" 0 0 0755
done
