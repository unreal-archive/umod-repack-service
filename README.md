# Unreal Archive UMOD Repack Service

A simple service for unpacking Unreal engine UMOD packages, and serving them 
for download as ZIP archives. 

- allows user to submit file via HTTP form upload
- submitted files enter a queue so as not to overload server resources
- queued files will be unzipped if needed, then UMOD files unpacked
- using the same path hierarchy defined in the UMOD, files will be zipped
- zip file will remain available for limited amount of time before expiring as
  the intent is not to offer a hosting service
