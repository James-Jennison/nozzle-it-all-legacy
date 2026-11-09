# syntax=docker/dockerfile:1
FROM sha256:98d0fed8d169104ba6abb0b1ed5045cdff9a070d2df08b30ce6e68622682d53c

COPY scripts/visual/install-image-deps.sh /opt/nia/install-image-deps.sh
RUN /opt/nia/install-image-deps.sh

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    __GLX_VENDOR_LIBRARY_NAME=mesa \
    SKIKO_RENDER_API=SOFTWARE
